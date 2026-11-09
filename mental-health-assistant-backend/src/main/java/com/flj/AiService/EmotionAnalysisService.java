package com.flj.AiService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallFailures;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.SessionEmotionResponseDTO;
import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.EmotionDiary;
import com.flj.mapper.ConsultationSessionMapper;
import com.flj.mapper.EmotionDiaryMapper;
import com.flj.service.ConsultationMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界执行分析；同一对象的请求共享任务和绝对截止时间，缓存按实际输入消息ID判新鲜。
 */
@Service
public class EmotionAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(EmotionAnalysisService.class);

    @Autowired
    @Qualifier("emotion-analysis")
    private ChatClient chatClient;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private AiCallGovernor governor;

    // 分析窗口：最近 20 条消息
    private static final int ANALYSIS_WINDOW_SIZE = 20;
    // 单条消息截断长度
    private static final int MAX_MESSAGE_LENGTH = 500;
    @Value("${app.ai.analysis-timeout-ms:60000}")
    private long analysisTimeoutMillis = 60000;
    @Value("${app.ai.analysis-workers:4}")
    private int analysisWorkers = 4;
    @Value("${app.ai.analysis-queue-capacity:16}")
    private int analysisQueueCapacity = 16;

    private final ConcurrentHashMap<String, AnalysisTask> inFlightAnalysis = new ConcurrentHashMap<>();
    private ThreadPoolExecutor workerExecutor;
    private ScheduledThreadPoolExecutor deadlineScheduler;

    @PostConstruct
    synchronized void initializeExecutors() {
        if (workerExecutor != null) { return; }
        if (analysisTimeoutMillis <= 0 || analysisWorkers <= 0 || analysisQueueCapacity <= 0) {
            throw new IllegalArgumentException("分析截止时间、线程数和队列容量必须大于零");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory workers = action -> {
            Thread thread = new Thread(action, "emotion-analysis-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workerExecutor = new ThreadPoolExecutor(analysisWorkers, analysisWorkers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(analysisQueueCapacity), workers, new ThreadPoolExecutor.AbortPolicy());
        deadlineScheduler = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "emotion-analysis-deadline");
            thread.setDaemon(true);
            return thread;
        });
        deadlineScheduler.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void shutdownExecutors() {
        for (AnalysisTask task : inFlightAnalysis.values()) { task.expire(); }
        if (workerExecutor != null) { workerExecutor.shutdownNow(); }
        if (deadlineScheduler != null) { deadlineScheduler.shutdownNow(); }
    }

    /**
     * 获取会话情绪快照：缓存新鲜直接返回，否则执行分析后返回
     */
    public SessionEmotionResponseDTO getOrAnalyzeEmotion(Long sessionId) {
        AnalysisTask task = startTask("session:" + sessionId, current -> analyzeSession(sessionId, current));
        try {
            return copy(await(task));
        } catch (RuntimeException error) {
            String status = failureStatus(error);
            log.warn("会话分析未完成, sessionId={}, status={}, errorType={}", sessionId, status, error.getClass().getSimpleName());
            return fallback(task.fallbackSnapshot, status);
        }
    }

    /**
     * 对会话近期消息执行 LLM 情绪分析，成功后写入情绪快照
     */
    private SessionEmotionResponseDTO analyzeSession(Long sessionId, AnalysisTask task) {
        ConsultationSession session = consultationSessionMapper.selectById(sessionId);
        task.checkActive();
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return fallback(null, "FAILED");
        }
        task.fallbackSnapshot = parseSnapshot(session.getLastEmotionAnalysis());
        ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(sessionId);
        task.checkActive();
        if (lastMessage == null) { return SessionEmotionResponseDTO.neutral(); }
        SessionEmotionResponseDTO cached = parseSnapshot(session.getLastEmotionAnalysis());
        if (cached != null && cached.getCoveredMessageId() != null
                && Objects.equals(cached.getCoveredMessageId(), lastMessage.getId())) {
            cached.setAnalysisStatus("SUCCEEDED");
            cached.setCached(true);
            cached.setStale(false);
            return cached;
        }
        List<ConsultationMessage> recentMessages = new ArrayList<>(
                consultationMessageService.getRecentMessages(session.getId(), ANALYSIS_WINDOW_SIZE));
        task.checkActive();
        if (recentMessages.isEmpty()) { return SessionEmotionResponseDTO.neutral(); }
        Long coveredMessageId = recentMessages.stream().map(ConsultationMessage::getId)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        // 取出为 id 倒序，反转为时间正序
        Collections.reverse(recentMessages);

        String material = formatMessages(recentMessages);
        try (AiCallGovernor.Lease lease = governor.acquire(session.getUserId(), "consultation-analysis")) {
            lease.validateInput(PromptManage.EMOTION_ANALYSIS_SYSTEM_PROMPT, material);
            task.checkActive();
            lease.markStarted();
            EmotionAnalysisResult result = callAnalysis(PromptManage.EMOTION_ANALYSIS_SYSTEM_PROMPT, material, task);
            task.checkActive();
            SessionEmotionResponseDTO dto = toResponseDTO(result);
            dto.setCoveredMessageId(coveredMessageId);
            ConsultationMessageResponseDTO latest = consultationMessageService.getLastMessageBySessionId(sessionId);
            task.checkActive();
            dto.setStale(latest != null && !Objects.equals(coveredMessageId, latest.getId()));
            task.checkActive();
            saveSnapshot(session, dto, task);
            return dto;
        }
    }

    /**
     * 日记不可变：同一日记的分析与条件落库共用任务；失败保留日记，不覆盖已有成功。
     */
    public SessionEmotionResponseDTO analyzeDiary(EmotionDiary diary) {
        if (diary.getId() == null) { throw new IllegalArgumentException("日记尚未保存"); }
        SessionEmotionResponseDTO existing = parseSnapshot(diary.getAiEmotionAnalysis());
        if (existing != null) { return existing; }
        if (StrUtil.isNotBlank(diary.getAiEmotionAnalysis())) {
            throw new AnalysisCallException("500", "已有分析结果暂不可用", UUID.randomUUID().toString(), "FAILED");
        }
        return copy(await(startTask("diary:" + diary.getId(), task -> analyzeAndSaveDiary(diary, task))));
    }

    private SessionEmotionResponseDTO analyzeAndSaveDiary(EmotionDiary requested, AnalysisTask task) {
        EmotionDiary diary = emotionDiaryMapper.selectById(requested.getId());
        if (diary == null || Boolean.TRUE.equals(diary.getDeleted())
                || !Objects.equals(requested.getUserId(), diary.getUserId())) {
            throw task.failure("404", "日记不存在", "FAILED");
        }
        SessionEmotionResponseDTO existing = parseSnapshot(diary.getAiEmotionAnalysis());
        if (existing != null) {
            existing.setCached(true);
            return existing;
        }
        if (StrUtil.isNotBlank(diary.getAiEmotionAnalysis())) {
            throw task.failure("500", "已有分析结果暂不可用", "FAILED");
        }
        String material = formatDiaryMaterial(diary);
        try (AiCallGovernor.Lease lease = governor.acquire(diary.getUserId(), "diary-analysis")) {
            lease.validateInput(PromptManage.DIARY_EMOTION_ANALYSIS_SYSTEM_PROMPT, material);
            task.checkActive();
            lease.markStarted();
            EmotionAnalysisResult result = callAnalysis(PromptManage.DIARY_EMOTION_ANALYSIS_SYSTEM_PROMPT, material, task);
            task.checkActive();
            SessionEmotionResponseDTO dto = toResponseDTO(result);
            LambdaUpdateWrapper<EmotionDiary> update = new LambdaUpdateWrapper<>();
            update.eq(EmotionDiary::getId, diary.getId()).eq(EmotionDiary::getUserId, diary.getUserId())
                    .eq(EmotionDiary::getDeleted, false).isNull(EmotionDiary::getAiEmotionAnalysis)
                    .set(EmotionDiary::getAiEmotionAnalysis, JSONUtil.toJsonStr(dto))
                    .set(EmotionDiary::getAiAnalysisUpdatedAt, dto.getAnalyzedAt());
            task.checkActive();
            if (emotionDiaryMapper.update(null, update) == 0) {
                EmotionDiary current = emotionDiaryMapper.selectById(diary.getId());
                SessionEmotionResponseDTO winner = current != null && !Boolean.TRUE.equals(current.getDeleted())
                        ? parseSnapshot(current.getAiEmotionAnalysis()) : null;
                if (winner == null) { throw task.failure("500", "日记分析未能保存", "FAILED"); }
                winner.setCached(true);
                return winner;
            }
            return dto;
        }
    }

    /**
     * 将日记素材格式化为分析输入文本（空字段标注"未填写"）
     */
    private String formatDiaryMaterial(EmotionDiary diary) {
        return "以下是一篇情绪日记，请分析日记主人的情绪状态：\n\n" +
                "日记日期：" + diary.getDiaryDate() + "\n" +
                "自评情绪分（1-10，主观）：" + diary.getMoodScore() + "\n" +
                "主要情绪：" + StrUtil.blankToDefault(diary.getDominantEmotion(), "未填写") + "\n" +
                "情绪触发因素：" + StrUtil.blankToDefault(diary.getEmotionTriggers(), "未填写") + "\n" +
                "今日感想：" + StrUtil.blankToDefault(diary.getDiaryContent(), "未填写") + "\n" +
                "睡眠质量（1-5）：" + (diary.getSleepQuality() != null ? diary.getSleepQuality() : "未填写") + "\n" +
                "压力水平（1-5）：" + (diary.getStressLevel() != null ? diary.getStressLevel() : "未填写");
    }

    private EmotionAnalysisResult callAnalysis(String system, String material, AnalysisTask task) {
        try {
            EmotionAnalysisResult result = chatClient.prompt().system(system).user(material)
                    .options(OpenAiChatOptions.builder().maxTokens(governor.getMaxOutputTokens()).build())
                    .call().entity(EmotionAnalysisResult.class);
            if (result == null) { throw new IllegalStateException("模型未返回有效分析"); }
            return result;
        } catch (RuntimeException error) {
            if (error instanceof AnalysisCallException || error instanceof AiCallRejectedException) { throw error; }
            if (AiCallFailures.isTimeout(error)) {
                throw new AnalysisCallException("504", "分析超时，请稍后再试", task.requestId, "TIMED_OUT", error);
            }
            throw new AnalysisCallException("502", "分析服务暂不可用", task.requestId, "FAILED", error);
        }
    }


    /**
     * 将窗口内消息格式化为分析输入文本（角色标注 + 单条截断）
     */
    private String formatMessages(List<ConsultationMessage> messages) {
        StringBuilder sb = new StringBuilder("以下是心理咨询对话记录（按时间正序），请分析其中用户的情绪状态：\n\n");
        for (ConsultationMessage message : messages) {
            String role = message.getSenderType() != null && message.getSenderType() == 2 ? "AI助手" : "用户";
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.length() > MAX_MESSAGE_LENGTH) {
                content = content.substring(0, MAX_MESSAGE_LENGTH);
            }
            sb.append(role).append("：").append(content).append('\n');
        }
        return sb.toString();
    }

    /**
     * 映射 LLM 结构化结果到响应契约（越界值钳制，空值兜底）
     */
    private SessionEmotionResponseDTO toResponseDTO(EmotionAnalysisResult result) {
        if (result == null) { throw new IllegalStateException("模型未返回有效分析"); }
        SessionEmotionResponseDTO dto = new SessionEmotionResponseDTO();
        dto.setPrimaryEmotion(StrUtil.blankToDefault(result.primaryEmotion(), "中性"));
        dto.setEmotionScore(clamp(result.emotionScore(), 0, 100, 50));
        dto.setIsNegative(Boolean.TRUE.equals(result.negative()));
        dto.setRiskLevel(clamp(result.riskLevel(), 0, 3, 0));
        dto.setRiskDescription(StrUtil.emptyIfNull(result.riskDescription()));
        dto.setSuggestion(StrUtil.emptyIfNull(result.suggestion()));
        dto.setImprovementSuggestions(result.improvementSuggestions() != null
                ? result.improvementSuggestions() : new ArrayList<>());
        dto.setAnalyzedAt(LocalDateTime.now());
        dto.setAnalysisStatus("SUCCEEDED");
        return dto;
    }

    /**
     * 写入情绪快照：last_emotion_analysis(JSON) + last_emotion_updated_at(分析完成时间)
     */
    private void saveSnapshot(ConsultationSession session, SessionEmotionResponseDTO dto, AnalysisTask task) {
        LambdaUpdateWrapper<ConsultationSession> update = new LambdaUpdateWrapper<>();
        update.eq(ConsultationSession::getId, session.getId())
                .eq(ConsultationSession::getUserId, session.getUserId())
                .eq(ConsultationSession::getDeleted, false)
                .set(ConsultationSession::getLastEmotionAnalysis, JSONUtil.toJsonStr(dto))
                .set(ConsultationSession::getLastEmotionUpdatedAt, dto.getAnalyzedAt());
        task.checkActive();
        if (consultationSessionMapper.update(null, update) != 1) {
            throw task.failure("500", "分析结果未能保存", "FAILED");
        }
    }

    /**
     * 分析失败降级：返回旧快照（若有），否则中性默认值
     */
    private SessionEmotionResponseDTO fallback(SessionEmotionResponseDTO snapshot, String status) {
        if (snapshot != null) {
            SessionEmotionResponseDTO old = copy(snapshot);
            old.setAnalysisStatus(status);
            old.setCached(true);
            old.setStale(true);
            return old;
        }
        SessionEmotionResponseDTO neutral = SessionEmotionResponseDTO.neutral();
        neutral.setAnalysisStatus(status);
        return neutral;
    }

    private SessionEmotionResponseDTO parseSnapshot(String json) {
        if (StrUtil.isBlank(json)) { return null; }
        try {
            return JSONUtil.toBean(json, SessionEmotionResponseDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private SessionEmotionResponseDTO copy(SessionEmotionResponseDTO source) {
        SessionEmotionResponseDTO target = new SessionEmotionResponseDTO();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private String failureStatus(RuntimeException error) {
        if (error instanceof AnalysisCallException failure) { return failure.getAnalysisStatus(); }
        if (error instanceof AiCallRejectedException) { return "REJECTED"; }
        return "FAILED";
    }

    @FunctionalInterface
    private interface AnalysisOperation {
        SessionEmotionResponseDTO execute(AnalysisTask task);
    }

    private AnalysisTask startTask(String key, AnalysisOperation operation) {
        initializeExecutors();
        AnalysisTask candidate = new AnalysisTask(key, operation);
        AnalysisTask existing = inFlightAnalysis.putIfAbsent(key, candidate);
        if (existing != null) { return existing; }
        try {
            candidate.deadlineFuture = deadlineScheduler.schedule(candidate::expire,
                    Math.max(0, candidate.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            workerExecutor.execute(candidate);
        } catch (RejectedExecutionException error) {
            candidate.reject();
        }
        return candidate;
    }

    private SessionEmotionResponseDTO await(AnalysisTask task) {
        try {
            long remaining = task.deadlineNanos - System.nanoTime();
            if (remaining <= 0) { task.expire(); }
            return task.result.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            task.expire();
            throw task.failure("504", "分析超时，请稍后再试", "TIMED_OUT");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw task.failure("503", "分析等待已中断", "INTERRUPTED");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) { throw runtime; }
            throw task.failure("502", "分析服务暂不可用", "FAILED");
        }
    }

    /** A completed/cancelled Future is not proof that its upstream worker has exited. */
    private final class AnalysisTask implements Runnable {
        private final String key;
        private final String requestId = UUID.randomUUID().toString();
        private final AnalysisOperation operation;
        private final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(analysisTimeoutMillis);
        private final CompletableFuture<SessionEmotionResponseDTO> result = new CompletableFuture<>();
        private volatile SessionEmotionResponseDTO fallbackSnapshot;
        private volatile ScheduledFuture<?> deadlineFuture;
        private Thread runner;
        private boolean expired;
        private boolean finished;

        private AnalysisTask(String key, AnalysisOperation operation) {
            this.key = key;
            this.operation = operation;
        }

        @Override
        public void run() {
            SessionEmotionResponseDTO value = null;
            RuntimeException failure = null;
            synchronized (this) { runner = Thread.currentThread(); }
            try {
                checkActive();
                value = operation.execute(this);
                checkActive();
            } catch (RuntimeException error) {
                failure = error instanceof AiCallRejectedException || error instanceof AnalysisCallException
                        ? error : new AnalysisCallException("500", "分析服务暂不可用", requestId, "FAILED", error);
            } finally {
                synchronized (this) {
                    finished = true;
                    runner = null;
                }
                cancelDeadline();
                inFlightAnalysis.remove(key, this);
                // An interrupted model must not leave the reusable executor thread interrupted.
                Thread.interrupted();
            }
            if (failure == null) { result.complete(value); }
            else { result.completeExceptionally(failure); }
        }

        private void checkActive() {
            synchronized (this) {
                if (!expired && !finished && System.nanoTime() < deadlineNanos) { return; }
            }
            expire();
            throw failure("504", "分析超时，请稍后再试", "TIMED_OUT");
        }

        private void expire() {
            synchronized (this) {
                if (finished) { return; }
                expired = true;
                if (runner != null) { runner.interrupt(); }
            }
            result.completeExceptionally(failure("504", "分析超时，请稍后再试", "TIMED_OUT"));
            // A successfully removed queued task cannot later enter its model call.
            if (workerExecutor != null && workerExecutor.remove(this)) {
                synchronized (this) { finished = true; }
                cancelDeadline();
                inFlightAnalysis.remove(key, this);
            }
        }

        private void reject() {
            synchronized (this) {
                expired = true;
                finished = true;
            }
            cancelDeadline();
            inFlightAnalysis.remove(key, this);
            result.completeExceptionally(failure("429", "分析服务繁忙，请稍后再试", "REJECTED"));
        }

        private void cancelDeadline() {
            ScheduledFuture<?> deadline = deadlineFuture;
            if (deadline != null) { deadline.cancel(false); }
        }

        private AnalysisCallException failure(String code, String message, String status) {
            return new AnalysisCallException(code, message, requestId, status);
        }
    }

    private int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
