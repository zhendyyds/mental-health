package com.flj.rag.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.governance.AiCallFailures;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.entity.AiRequestRecord;
import com.flj.rag.IndexChunk;
import com.flj.rag.KnowledgeQaRequestRepository;
import com.flj.rag.RagEvidence;
import com.flj.rag.RagIndexException;
import com.flj.rag.RagRetrievalService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KnowledgeQaService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaService.class);
    private static final String SCENE = "knowledge-qa";
    private static final String SOURCE_CHANGED_CODE = "SRC_409";
    private static final Set<String> FAILURE_CODES = Set.of("400", "429", "500", "502", "503", "504");
    private static final int MAX_QUESTION_LENGTH = 1000;

    private final RagRetrievalService retrieval;
    private final KnowledgeQaRequestRepository ledger;
    private final AiCallGovernor governor;
    private final ChatClient model;
    private final KnowledgeQaPrompt prompt;
    private final String modelName;
    private final long timeoutNanos;
    private final ThreadPoolExecutor workers;
    private final QaResultCache cache;
    private final Map<RequestKey, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    public KnowledgeQaService(RagRetrievalService retrieval, KnowledgeQaRequestRepository ledger, AiCallGovernor governor,
                              ChatClient model, ObjectMapper mapper) {
        this(retrieval, ledger, governor, model, mapper, SCENE, 60_000, 4, 16, 128, 2_097_152, 900_000);
    }

    @Autowired
    public KnowledgeQaService(RagRetrievalService retrieval, KnowledgeQaRequestRepository ledger, AiCallGovernor governor,
                              @Qualifier("knowledge-qa") ChatClient model, ObjectMapper mapper,
                              @Value("${spring.ai.openai.chat.options.model:knowledge-qa}") String modelName,
                              @Value("${app.rag.qa.timeout-ms:60000}") long timeoutMillis,
                              @Value("${app.rag.qa.workers:4}") int workerCount,
                              @Value("${app.rag.qa.queue-capacity:16}") int queueCapacity,
                              @Value("${app.rag.qa.cache-max-entries:128}") int cacheEntries,
                              @Value("${app.rag.qa.cache-max-bytes:2097152}") long cacheBytes,
                              @Value("${app.rag.qa.cache-ttl-ms:900000}") long cacheTtlMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 60_000 || workerCount < 1 || workerCount > 16
                || queueCapacity < 1 || queueCapacity > 128 || cacheEntries < 1 || cacheEntries > 1024
                || cacheBytes < 1 || cacheBytes > 67_108_864 || cacheTtlMillis < 1 || cacheTtlMillis > 900_000) {
            throw new IllegalArgumentException("Invalid knowledge QA limits");
        }
        this.retrieval = retrieval;
        this.ledger = ledger;
        this.governor = governor;
        this.model = model;
        this.prompt = new KnowledgeQaPrompt(mapper);
        this.modelName = modelName != null && modelName.matches("[A-Za-z0-9_./:-]{1,100}") ? modelName : SCENE;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.cache = new QaResultCache(cacheEntries, cacheBytes, TimeUnit.MILLISECONDS.toNanos(cacheTtlMillis));
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "knowledge-qa-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** The caller and actual worker share one deadline from the request's arrival. */
    public QaResponse ask(long userId, String question, String clientRequestId) {
        Attempt attempt = new Attempt(UUID.randomUUID().toString(), System.nanoTime() + timeoutNanos);
        if (userId <= 0) { throw failure("401", "FAILED", "请先登录", attempt.requestId); }
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH
                || clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9_-]{8,128}")) {
            throw failure("400", "FAILED", "请输入不超过 1000 字的问题和有效请求编号", attempt.requestId);
        }
        String normalized = question.strip();
        String fingerprint = IndexChunk.hashContent(String.join("\0", "knowledge-qa-v1", Long.toString(userId),
                clientRequestId, normalized));
        RequestKey key = new RequestKey(userId, clientRequestId);
        ActiveRequest active = activeRequests.get(key);
        if (active != null) { throw activeFailure(active, fingerprint); }
        Future<QaResponse> work;
        try {
            work = workers.submit(() -> execute(attempt, key, normalized, fingerprint));
        } catch (RejectedExecutionException rejected) {
            throw new QaFailure("429", "FAILED", "知识问答暂时繁忙，请稍后再试", attempt.requestId, 1);
        }
        try {
            return work.get(Math.max(0, attempt.remainingNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException elapsed) {
            cancel(attempt, work);
            throw attempt.timeout();
        } catch (InterruptedException interrupted) {
            cancel(attempt, work);
            Thread.currentThread().interrupt();
            throw failure("503", "UNKNOWN", "请求等待已中断，结果待确认", attempt.requestId);
        } catch (CancellationException cancelled) {
            throw failure("503", "UNKNOWN", "请求等待已中断，结果待确认", attempt.requestId);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof QaFailure error) { throw error; }
            log.warn("Knowledge QA worker failed requestId={} type={}", attempt.requestId, failed.getCause().getClass().getSimpleName());
            throw failure("503", "UNKNOWN", "请求结果暂时无法确认", attempt.requestId);
        }
    }

    private void cancel(Attempt attempt, Future<?> work) {
        attempt.expired = true;
        work.cancel(true);
        // Queued work never acquired a ledger record. Running threads keep their actual slot until exit.
        if (work instanceof Runnable queued) { workers.remove(queued); }
    }

    private QaResponse execute(Attempt attempt, RequestKey key, String question, String fingerprint) {
        try {
            attempt.check();
            AiRequestRecord existing = ledger.find(key.userId(), key.clientId());
            if (existing != null) { return replay(attempt, existing, fingerprint); }

            ActiveRequest reserved = new ActiveRequest(attempt.requestId, fingerprint, attempt);
            ActiveRequest competing = activeRequests.putIfAbsent(key, reserved);
            if (competing != null) { throw activeFailure(competing, fingerprint); }
            try {
                attempt.check();
                AiRequestRecord record;
                try {
                    record = ledger.create(key.userId(), key.clientId(), fingerprint, modelName, reserved.requestId());
                } catch (DuplicateKeyException duplicate) {
                    activeRequests.remove(key, reserved);
                    AiRequestRecord winner = ledger.find(key.userId(), key.clientId());
                    if (winner == null) { throw unknown(attempt.requestId); }
                    return replay(attempt, winner, fingerprint);
                }
                return generate(attempt, record, question);
            } finally {
                activeRequests.remove(key, reserved);
            }
        } catch (QaFailure error) {
            throw error;
        } catch (RuntimeException error) {
            if (attempt.elapsed() || AiCallFailures.isTimeout(error)) { throw attempt.timeout(); }
            log.warn("Knowledge QA state unavailable requestId={} type={}", attempt.requestId, error.getClass().getSimpleName());
            throw unknown(attempt.requestId);
        }
    }

    private QaResponse generate(Attempt attempt, AiRequestRecord record, String question) {
        try {
            attempt.check();
            try (AiCallGovernor.Lease lease = governor.acquire(record.getUserId(), SCENE)) {
                lease.validateInput(question);
                attempt.check();
                if (!ledger.markDispatched(record)) { throw unknown(attempt.requestId); }
                attempt.check();
                // This lease limits one logical QA operation, including empty retrieval. The embedding gateway has its own budget.
                lease.markStarted();
                List<RagEvidence> evidence = retrieval.retrieve(record.getUserId(), question).stream().limit(6).toList();
                attempt.check();
                QaResponse response = KnowledgeQaPrompt.noEvidence(attempt.requestId);
                if (!evidence.isEmpty()) {
                    verifySources(attempt, evidence);
                    String input = prompt.userMessage(question, evidence);
                    lease.validateInput(KnowledgeQaPrompt.SYSTEM, input);
                    attempt.check();
                    String raw = selectQuotes(attempt, input);
                    attempt.check();
                    response = prompt.validate(raw, evidence, attempt.requestId, governor.getMaxReplyBytes());
                    verifySources(attempt, evidence);
                }
                attempt.check();
                if (!ledger.finish(record, "SUCCEEDED", null)) { throw unknown(attempt.requestId); }
                attempt.check();
                if (workers.isShutdown()) { throw unknown(attempt.requestId); }
                cache.put(response, evidence);
                return response;
            }
        } catch (RuntimeException error) {
            QaFailure safe = classify(attempt, error);
            throw settleFailure(record, safe);
        }
    }

    private String selectQuotes(Attempt attempt, String input) {
        try {
            var request = model.prompt().user(input)
                    .options(OpenAiChatOptions.builder().maxTokens(governor.getMaxOutputTokens()).temperature(0.0)
                            .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                            .internalToolExecutionEnabled(false).build());
            attempt.check();
            return request.call().content();
        } catch (RuntimeException error) {
            if (attempt.elapsed() || AiCallFailures.isTimeout(error)) { throw attempt.timeout(); }
            throw failure("502", "UNKNOWN", "问答模型结果待确认，请勿重复提交", attempt.requestId);
        }
    }

    private QaResponse replay(Attempt attempt, AiRequestRecord record, String fingerprint) {
        attempt.requestId = record.getRequestId();
        attempt.check();
        if (!fingerprint.equals(record.getRequestFingerprint())) {
            throw conflict(attempt.requestId);
        }
        if ("RUNNING".equals(record.getStatus())) {
            ActiveRequest active = activeRecord(record);
            if (active != null) { throw activeFailure(active, fingerprint); }
            if (ledger.finish(record, "UNKNOWN", "409")) { throw priorUnknown(attempt.requestId); }
            // The original worker can finish between our read and the active-set check.
            record = ledger.find(record.getUserId(), record.getClientRequestId());
            attempt.check();
            if (record == null || "RUNNING".equals(record.getStatus())) { throw priorUnknown(attempt.requestId); }
            if (!attempt.requestId.equals(record.getRequestId())) { throw unavailable(attempt.requestId); }
        }
        return switch (record.getStatus()) {
            case "SUCCEEDED" -> replayResult(attempt, record);
            case "FAILED" -> throw priorFailure(record);
            default -> throw priorUnknown(attempt.requestId);
        };
    }

    private QaResponse replayResult(Attempt attempt, AiRequestRecord record) {
        QaResultCache.Entry cached = cache.get(record.getRequestId());
        if (cached == null) {
            ActiveRequest active = activeRecord(record);
            if (active != null) { throw activeFailure(active, record.getRequestFingerprint()); }
            throw unavailable(attempt.requestId);
        }
        verifySources(attempt, cached.evidence());
        attempt.check();
        return cached.response();
    }

    private void verifySources(Attempt attempt, List<RagEvidence> evidence) {
        if (evidence.isEmpty()) { return; }
        attempt.check();
        List<RagEvidence> current = retrieval.revalidate(evidence);
        attempt.check();
        if (!evidence.equals(current)) { throw sourceChanged(attempt.requestId); }
    }

    private QaFailure settleFailure(AiRequestRecord record, QaFailure error) {
        String status = "UNKNOWN".equals(error.getStatus()) ? "UNKNOWN" : "FAILED";
        String code = "UNKNOWN".equals(status) ? "409"
                : "SOURCE_CHANGED".equals(error.getStatus()) ? SOURCE_CHANGED_CODE : error.getCode();
        // The caller may have interrupted this thread. Reconcile the durable state before releasing the actual worker.
        Thread.interrupted();
        try {
            ledger.finish(record, status, code);
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge QA terminal state unavailable requestId={} type={}", record.getRequestId(), unavailable.getClass().getSimpleName());
            if (!"UNKNOWN".equals(status)) { return unknown(record.getRequestId()); }
        }
        return error;
    }

    private QaFailure classify(Attempt attempt, RuntimeException error) {
        if (attempt.elapsed() || AiCallFailures.isTimeout(error)) { return attempt.timeout(); }
        if (error instanceof QaFailure known) { return known; }
        if (error instanceof AiCallRejectedException rejected) {
            String code = safeCode(rejected.getCode());
            int retryAfter = "429".equals(code) ? Math.max(1, rejected.getRetryAfterSeconds()) : 0;
            return new QaFailure(code, "FAILED", rejected.getMessage(), attempt.requestId, retryAfter);
        }
        if (error instanceof RagIndexException indexFailure) {
            String code = safeCode(indexFailure.getCode());
            if ("504".equals(code)) { return attempt.timeout(); }
            return new QaFailure(code, "FAILED", "知识检索暂不可用，请稍后再试", attempt.requestId, "429".equals(code) ? 1 : 0);
        }
        if (error instanceof DataAccessException) { return unknown(attempt.requestId); }
        log.warn("Knowledge QA failed requestId={} type={}", attempt.requestId, error.getClass().getSimpleName());
        return failure("500", "FAILED", "知识问答暂不可用", attempt.requestId);
    }

    private static QaFailure priorFailure(AiRequestRecord record) {
        if (SOURCE_CHANGED_CODE.equals(record.getErrorCode())) { return sourceChanged(record.getRequestId()); }
        String code = safeCode(record.getErrorCode());
        return new QaFailure(code, "FAILED", "该请求已失败，可稍后发起新请求", record.getRequestId(), "429".equals(code) ? 1 : 0);
    }

    private static String safeCode(String code) { return code != null && FAILURE_CODES.contains(code) ? code : "500"; }
    private static QaFailure conflict(String id) { return failure("409", "CONFLICT", "同一请求编号的参数已改变，请发起新请求", id); }
    private static QaFailure sourceChanged(String id) { return failure("409", "SOURCE_CHANGED", "引用资料已更新，请重新提问", id); }
    private static QaFailure running(String id) { return failure("409", "RUNNING", "同一请求正在处理，请稍后查看", id); }
    private static QaFailure priorUnknown(String id) { return failure("409", "UNKNOWN", "原请求结果待确认，请勿重复提交", id); }
    private static QaFailure unknown(String id) { return failure("503", "UNKNOWN", "请求结果暂时无法确认", id); }
    private static QaFailure unavailable(String id) { return failure("409", "RESULT_UNAVAILABLE", "原结果已过期或不可重放，可发起新请求", id); }
    private static QaFailure failure(String code, String status, String message, String id) { return new QaFailure(code, status, message, id); }

    private ActiveRequest activeRecord(AiRequestRecord record) {
        ActiveRequest active = activeRequests.get(new RequestKey(record.getUserId(), record.getClientRequestId()));
        return active != null && active.requestId().equals(record.getRequestId()) ? active : null;
    }

    private static QaFailure activeFailure(ActiveRequest active, String fingerprint) {
        if (!active.fingerprint().equals(fingerprint)) { return conflict(active.requestId()); }
        if (active.attempt().expired || active.attempt().remainingNanos() <= 0) { return priorUnknown(active.requestId()); }
        return running(active.requestId());
    }

    @Scheduled(fixedDelay = 30_000)
    public void removeExpiredResults() { cache.purgeExpired(); }

    @Override
    @PreDestroy
    public void close() {
        for (Runnable queued : workers.shutdownNow()) {
            if (queued instanceof Future<?> future) { future.cancel(false); }
        }
        cache.clear();
    }

    private record RequestKey(long userId, String clientId) { }
    private record ActiveRequest(String requestId, String fingerprint, Attempt attempt) { }

    private static final class Attempt {
        private volatile String requestId;
        private final long deadlineNanos;
        private volatile boolean expired;

        private Attempt(String requestId, long deadlineNanos) {
            this.requestId = requestId;
            this.deadlineNanos = deadlineNanos;
        }

        private long remainingNanos() { return deadlineNanos - System.nanoTime(); }

        private boolean elapsed() { return expired || remainingNanos() <= 0 || Thread.currentThread().isInterrupted(); }

        private void check() {
            if (elapsed()) { throw timeout(); }
        }

        private QaFailure timeout() { return failure("504", "UNKNOWN", "知识问答等待超时，结果待确认", requestId); }
    }
}
