package com.flj.AiService;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.AiService.governance.AiProtectionProperties;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.SessionEmotionResponseDTO;
import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.EmotionDiary;
import com.flj.mapper.ConsultationSessionMapper;
import com.flj.mapper.EmotionDiaryMapper;
import com.flj.service.ConsultationMessageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDate;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmotionAnalysisServiceTest {
    private EmotionAnalysisService service;
    private ConsultationSessionMapper sessions;
    private EmotionDiaryMapper diaries;
    private ConsultationMessageService messages;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec prompt;
    private ChatClient.CallResponseSpec call;
    private AiCallGovernor governor;
    private AiProtectionProperties limits;
    private ExecutorService callers;
    private final List<CountDownLatch> releaseGates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test-diary"), EmotionDiary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test-session"), ConsultationSession.class);
        service = new EmotionAnalysisService();
        sessions = mock(ConsultationSessionMapper.class);
        diaries = mock(EmotionDiaryMapper.class);
        messages = mock(ConsultationMessageService.class);
        client = mock(ChatClient.class);
        prompt = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);
        limits = new AiProtectionProperties();
        governor = new AiCallGovernor(limits);
        callers = Executors.newCachedThreadPool();
        ReflectionTestUtils.setField(service, "consultationSessionMapper", sessions);
        ReflectionTestUtils.setField(service, "emotionDiaryMapper", diaries);
        ReflectionTestUtils.setField(service, "consultationMessageService", messages);
        ReflectionTestUtils.setField(service, "chatClient", client);
        ReflectionTestUtils.setField(service, "governor", governor);
        ReflectionTestUtils.setField(service, "analysisWorkers", 1);
        ReflectionTestUtils.setField(service, "analysisQueueCapacity", 2);
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 1500L);
        when(client.prompt()).thenReturn(prompt);
        when(prompt.system(anyString())).thenReturn(prompt);
        when(prompt.user(anyString())).thenReturn(prompt);
        when(prompt.options(any(ChatOptions.class))).thenReturn(prompt);
        when(prompt.call()).thenReturn(call);
        when(call.entity(EmotionAnalysisResult.class)).thenReturn(result());
        when(sessions.selectById(anyLong())).thenAnswer(invocation ->
                ConsultationSession.builder().id(invocation.getArgument(0)).userId(7L).deleted(false).build());
        when(messages.getLastMessageBySessionId(anyLong())).thenReturn(last(10L));
        when(messages.getRecentMessages(anyLong(), eq(20))).thenReturn(List.of(message(10L, "用户内容")));
        when(diaries.selectById(anyLong())).thenAnswer(invocation -> diary(invocation.getArgument(0)));
        when(diaries.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(sessions.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        releaseGates.forEach(CountDownLatch::countDown);
        service.shutdownExecutors();
        callers.shutdownNow();
    }

    @Test
    void firstRequestHasRealDeadlineAndLateModelCannotWriteOrFreeItsLeaseEarly() throws Exception {
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 150L);
        limits.setMaxConcurrent(1);
        ReflectionTestUtils.setField(service, "governor", new AiCallGovernor(limits));
        governor = (AiCallGovernor) ReflectionTestUtils.getField(service, "governor");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown();
            awaitUninterruptibly(release);
            return result();
        });
        long started = System.nanoTime();
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals("TIMED_OUT", first.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertEquals("TIMED_OUT", service.getOrAnalyzeEmotion(1L).getAnalysisStatus());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(8L, "consultation-analysis"));
        assertEquals(1, tasks().size());
        release.countDown();
        awaitWorker();
        assertTrue(tasks().isEmpty());
        verify(sessions, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        try (AiCallGovernor.Lease ignored = governor.acquire(8L, "consultation-analysis")) { }
    }

    @Test
    void joinersShareOneTaskAndOriginalDeadlineEvenWhenConfigurationChanges() throws Exception {
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 180L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 5000L);
        long joined = System.nanoTime();
        Future<SessionEmotionResponseDTO> second = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertEquals("TIMED_OUT", second.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        assertEquals("TIMED_OUT", first.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - joined) < 1000);
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
    }

    @Test
    void successfulConcurrentRequestsShareOneAnalysisAndOneSnapshotWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        AtomicReference<Thread> joiningThread = new AtomicReference<>();
        Future<SessionEmotionResponseDTO> second = callers.submit(() -> {
            joiningThread.set(Thread.currentThread());
            return service.getOrAnalyzeEmotion(1L);
        });
        awaitWaiting(joiningThread);
        release.countDown();
        assertEquals("SUCCEEDED", first.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        assertEquals("SUCCEEDED", second.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        verify(sessions, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void snapshotCoverageUsesActualInputAndNewMessageForcesNextAnalysis() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        when(messages.getLastMessageBySessionId(1L)).thenReturn(last(11L));
        release.countDown();
        SessionEmotionResponseDTO initial = first.get(2, TimeUnit.SECONDS);
        assertEquals(10L, initial.getCoveredMessageId());
        assertTrue(initial.isStale());
        ArgumentCaptor<LambdaUpdateWrapper<ConsultationSession>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessions).update(isNull(), update.capture());
        String json = update.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof String && ((String) value).contains("coveredMessageId"))
                .map(String.class::cast).findFirst().orElseThrow();
        ConsultationSession persisted = ConsultationSession.builder().id(1L).userId(7L).deleted(false)
                .lastEmotionAnalysis(json).build();
        when(sessions.selectById(1L)).thenReturn(persisted);
        when(messages.getRecentMessages(1L, 20)).thenReturn(List.of(message(11L, "新消息"), message(10L, "旧消息")));
        when(call.entity(EmotionAnalysisResult.class)).thenReturn(result());
        SessionEmotionResponseDTO next = service.getOrAnalyzeEmotion(1L);
        assertEquals(11L, next.getCoveredMessageId());
        verify(call, times(2)).entity(EmotionAnalysisResult.class);
    }

    @Test
    void coverageCacheIgnoresWallClockAndLegacySnapshotWithoutCoverageIsRecomputed() {
        SessionEmotionResponseDTO snapshot = snapshot(10L);
        ConsultationSession row = ConsultationSession.builder().id(1L).userId(7L).deleted(false)
                .lastEmotionAnalysis(JSONUtil.toJsonStr(snapshot)).lastEmotionUpdatedAt(LocalDateTime.of(2000, 1, 1, 0, 0)).build();
        when(sessions.selectById(1L)).thenReturn(row);
        SessionEmotionResponseDTO cached = service.getOrAnalyzeEmotion(1L);
        assertTrue(cached.isCached());
        assertFalse(cached.isStale());
        verifyNoInteractions(client);
        snapshot.setCoveredMessageId(null);
        row.setLastEmotionAnalysis(JSONUtil.toJsonStr(snapshot));
        assertFalse(service.getOrAnalyzeEmotion(1L).isCached());
        verify(call).entity(EmotionAnalysisResult.class);
    }

    @Test
    void modelFailureReturnsOldSnapshotAsStaleWithoutChangingItsAnalysisTime() {
        SessionEmotionResponseDTO old = snapshot(9L);
        when(sessions.selectById(1L)).thenReturn(ConsultationSession.builder().id(1L).userId(7L)
                .lastEmotionAnalysis(JSONUtil.toJsonStr(old)).deleted(false).build());
        when(call.entity(EmotionAnalysisResult.class)).thenThrow(new IllegalStateException("SECRET provider response"));
        SessionEmotionResponseDTO failed = service.getOrAnalyzeEmotion(1L);
        assertEquals("FAILED", failed.getAnalysisStatus());
        assertEquals(old.getAnalyzedAt(), failed.getAnalyzedAt());
        assertEquals(9L, failed.getCoveredMessageId());
        assertTrue(failed.isCached());
        assertTrue(failed.isStale());
        verify(sessions, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void boundedQueueRejectsOverflowBeforeAnotherModelCall() throws Exception {
        ReflectionTestUtils.setField(service, "analysisQueueCapacity", 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<?> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Future<?> queued = callers.submit(() -> service.getOrAnalyzeEmotion(2L));
        // The second request queues before any of its database reads begin.
        awaitTaskCount(2);
        SessionEmotionResponseDTO rejected = service.getOrAnalyzeEmotion(3L);
        assertEquals("REJECTED", rejected.getAnalysisStatus());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        queued.get(2, TimeUnit.SECONDS);
    }

    @Test
    void queuedDeadlineRemovesUnstartedTaskWithoutConsumingModelBudget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<?> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 100L);
        assertEquals("TIMED_OUT", service.getOrAnalyzeEmotion(2L).getAnalysisStatus());
        assertFalse(tasks().containsKey("session:2"));
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    @Test
    void inputBudgetRejectsBeforeModelAndOutputLimitIsAppliedToActualOptions() {
        limits.setMaxInputUnits(64);
        limits.setReservedInputUnits(1);
        governor = new AiCallGovernor(limits);
        ReflectionTestUtils.setField(service, "governor", governor);
        assertEquals("REJECTED", service.getOrAnalyzeEmotion(1L).getAnalysisStatus());
        verify(client, never()).prompt();
        limits.setMaxInputUnits(32768);
        limits.setMaxOutputTokens(17);
        assertEquals("SUCCEEDED", service.getOrAnalyzeEmotion(2L).getAnalysisStatus());
        ArgumentCaptor<ChatOptions> options = ArgumentCaptor.forClass(ChatOptions.class);
        verify(prompt).options(options.capture());
        assertEquals(17, options.getValue().getMaxTokens());
    }

    @Test
    void diaryDeduplicationIncludesPersistenceAndProtectsSuccessfulConcurrentResult() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(diaries.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(invocation -> {
            saving.countDown(); awaitUninterruptibly(release); return 1;
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.analyzeDiary(diary(4L)));
        assertTrue(saving.await(2, TimeUnit.SECONDS));
        AtomicReference<Thread> joiningThread = new AtomicReference<>();
        Future<SessionEmotionResponseDTO> second = callers.submit(() -> {
            joiningThread.set(Thread.currentThread());
            return service.analyzeDiary(diary(4L));
        });
        awaitWaiting(joiningThread);
        release.countDown();
        assertEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        ArgumentCaptor<LambdaUpdateWrapper<EmotionDiary>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(diaries, times(1)).update(isNull(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id") && sql.contains("deleted") && sql.contains("ai_emotion_analysis IS NULL"));
    }

    @Test
    void diaryConflictReturnsWinnerWithoutOverwritingOrReanalyzing() {
        EmotionDiary original = diary(4L);
        EmotionDiary winner = diary(4L);
        SessionEmotionResponseDTO existing = snapshot(null);
        existing.setPrimaryEmotion("已有成功");
        winner.setAiEmotionAnalysis(JSONUtil.toJsonStr(existing));
        when(diaries.selectById(4L)).thenReturn(original, winner);
        when(diaries.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        SessionEmotionResponseDTO result = service.analyzeDiary(original);
        assertEquals("已有成功", result.getPrimaryEmotion());
        assertTrue(result.isCached());
        verify(diaries).update(isNull(), any(LambdaUpdateWrapper.class));
        assertEquals("已有成功", service.analyzeDiary(winner).getPrimaryEmotion());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
    }

    @Test
    void diaryTimeoutIsPublic504AndLateResultNeverWrites() throws Exception {
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 100L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.analyzeDiary(diary(4L)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        java.util.concurrent.ExecutionException wrapped = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> first.get(2, TimeUnit.SECONDS));
        AnalysisCallException error = assertInstanceOf(AnalysisCallException.class, wrapped.getCause());
        assertEquals("504", error.getCode());
        assertNotNull(error.getRequestId());
        assertThrows(AnalysisCallException.class, () -> service.analyzeDiary(diary(4L)));
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
        release.countDown();
        awaitWorker();
        verify(diaries, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void diaryWorkerReadsExistingSuccessBeforeStartingModel() {
        EmotionDiary existing = diary(4L);
        existing.setAiEmotionAnalysis(JSONUtil.toJsonStr(snapshot(null)));
        when(diaries.selectById(4L)).thenReturn(existing);
        assertTrue(service.analyzeDiary(diary(4L)).isCached());
        verifyNoInteractions(client);
    }

    @Test
    void invalidExecutorSettingsFailFastAndEmptySessionsDoNotCallTheModel() {
        when(messages.getLastMessageBySessionId(1L)).thenReturn(null);
        assertEquals("EMPTY", service.getOrAnalyzeEmotion(1L).getAnalysisStatus());
        verifyNoInteractions(client);
        EmotionAnalysisService invalid = new EmotionAnalysisService();
        ReflectionTestUtils.setField(invalid, "analysisQueueCapacity", 0);
        assertThrows(IllegalArgumentException.class, invalid::initializeExecutors);
    }

    @Test
    void blockedInitialDatabaseReadIsInsideDeadlineAndLateReadCannotStartModel() throws Exception {
        ReflectionTestUtils.setField(service, "analysisTimeoutMillis", 100L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(sessions.selectById(1L)).thenAnswer(invocation -> {
            entered.countDown();
            awaitUninterruptibly(release);
            return ConsultationSession.builder().id(1L).userId(7L).deleted(false).build();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        SessionEmotionResponseDTO timedOut = first.get(1, TimeUnit.SECONDS);
        assertEquals("TIMED_OUT", timedOut.getAnalysisStatus());
        assertFalse(timedOut.isCached());
        assertFalse(timedOut.isStale());
        assertNull(timedOut.getAnalyzedAt());
        assertEquals("TIMED_OUT", service.getOrAnalyzeEmotion(1L).getAnalysisStatus());
        verify(sessions, times(1)).selectById(1L);
        verifyNoInteractions(client);
        release.countDown();
        awaitWorker();
        verifyNoInteractions(messages);
        verify(sessions, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotWriteIsGuardedAgainstDeletionAndDatabaseFailure(boolean databaseFailure) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        when(sessions.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<ConsultationSession> wrapper = invocation.getArgument(1);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("user_id") && sql.contains("deleted") && sql.contains("id"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue(false));
            if (databaseFailure) { throw new IllegalStateException("SECRET SQL response"); }
            return 0; // The row was deleted while the model was running.
        });
        Future<SessionEmotionResponseDTO> request = callers.submit(() -> service.getOrAnalyzeEmotion(1L));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        SessionEmotionResponseDTO failed = request.get(2, TimeUnit.SECONDS);
        assertEquals("FAILED", failed.getAnalysisStatus());
        assertFalse(failed.isCached());
        assertNull(failed.getAnalyzedAt());
    }

    @Test
    void inputReadAfterRequestStartsCanCoverTheNewMessageWithoutFalseStaleness() {
        when(messages.getLastMessageBySessionId(1L)).thenReturn(last(10L), last(11L));
        when(messages.getRecentMessages(1L, 20)).thenReturn(List.of(message(11L, "新输入"), message(10L, "旧输入")));
        SessionEmotionResponseDTO fresh = service.getOrAnalyzeEmotion(1L);
        assertEquals(11L, fresh.getCoveredMessageId());
        assertFalse(fresh.isStale());
    }

    @Test
    void interruptingOneWaiterDoesNotCancelTheSharedModelTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        when(call.entity(EmotionAnalysisResult.class)).thenAnswer(invocation -> {
            entered.countDown(); awaitUninterruptibly(release); return result();
        });
        Future<SessionEmotionResponseDTO> first = callers.submit(() -> {
            waitingThread.set(Thread.currentThread());
            SessionEmotionResponseDTO response = service.getOrAnalyzeEmotion(1L);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            return response;
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        waitingThread.get().interrupt();
        assertEquals("INTERRUPTED", first.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        assertTrue(interruptPreserved.get());
        AtomicReference<Thread> joined = new AtomicReference<>();
        Future<SessionEmotionResponseDTO> second = callers.submit(() -> {
            joined.set(Thread.currentThread());
            return service.getOrAnalyzeEmotion(1L);
        });
        awaitWaiting(joined);
        release.countDown();
        assertEquals("SUCCEEDED", second.get(2, TimeUnit.SECONDS).getAnalysisStatus());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
    }

    @Test
    void diaryModelFailureIsRedacted502AndDeletedDiaryNeverCallsModel() {
        when(call.entity(EmotionAnalysisResult.class)).thenThrow(new IllegalStateException("SECRET model payload"));
        AnalysisCallException failure = assertThrows(AnalysisCallException.class, () -> service.analyzeDiary(diary(4L)));
        assertEquals("502", failure.getCode());
        assertFalse(failure.getMessage().contains("SECRET"));
        EmotionDiary deleted = diary(5L);
        deleted.setDeleted(true);
        when(diaries.selectById(5L)).thenReturn(deleted);
        assertEquals("404", assertThrows(AnalysisCallException.class,
                () -> service.analyzeDiary(diary(5L))).getCode());
        verify(call, times(1)).entity(EmotionAnalysisResult.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk-http", "socket", "reactor"})
    void knownUpstreamReadTimeoutsAre504WithoutExposingProviderMessages(String transport) {
        RuntimeException upstream = switch (transport) {
            case "jdk-http" -> new ResourceAccessException("SECRET request context", new HttpTimeoutException("SECRET timeout"));
            case "socket" -> new ResourceAccessException("SECRET request context", new SocketTimeoutException("SECRET timeout"));
            default -> new IllegalStateException("SECRET request context", new java.util.concurrent.TimeoutException("SECRET timeout"));
        };
        when(call.entity(EmotionAnalysisResult.class)).thenThrow(upstream);
        AnalysisCallException failure = assertThrows(AnalysisCallException.class, () -> service.analyzeDiary(diary(4L)));
        assertEquals("504", failure.getCode());
        assertEquals("TIMED_OUT", failure.getAnalysisStatus());
        assertFalse(failure.getMessage().contains("SECRET"));
        SessionEmotionResponseDTO fallback = service.getOrAnalyzeEmotion(1L);
        assertEquals("TIMED_OUT", fallback.getAnalysisStatus());
        assertNull(fallback.getAnalyzedAt());
        assertFalse(fallback.isCached());
        assertFalse(fallback.isStale());
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        releaseGates.add(gate);
        return gate;
    }

    private static void awaitUninterruptibly(CountDownLatch gate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        boolean interrupted = false;
        try {
            while (gate.getCount() != 0) {
                try { assertTrue(gate.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> tasks() {
        return (Map<String, ?>) ReflectionTestUtils.getField(service, "inFlightAnalysis");
    }

    private void awaitTaskCount(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (tasks().size() < count && System.nanoTime() < deadline) { Thread.onSpinWait(); }
        assertEquals(count, tasks().size());
    }

    private static void awaitWaiting(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Thread current = thread.get();
            if (current != null && current.getState() == Thread.State.TIMED_WAITING) { return; }
            Thread.onSpinWait();
        }
        fail("joining caller did not reach the shared task wait");
    }

    private void awaitWorker() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "workerExecutor");
        assertNotNull(executor);
        executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
    }

    private static EmotionAnalysisResult result() {
        return new EmotionAnalysisResult("平静", 70, false, 0, "", "建议", List.of("散步"));
    }

    private static SessionEmotionResponseDTO snapshot(Long covered) {
        SessionEmotionResponseDTO dto = SessionEmotionResponseDTO.neutral();
        dto.setCoveredMessageId(covered);
        dto.setAnalyzedAt(LocalDateTime.of(2026, 11, 1, 12, 0));
        dto.setAnalysisStatus("SUCCEEDED");
        return dto;
    }

    private static ConsultationMessageResponseDTO last(Long id) {
        ConsultationMessageResponseDTO dto = new ConsultationMessageResponseDTO();
        dto.setId(id);
        dto.setCreatedAt(LocalDateTime.of(2026, 11, 1, 12, 0));
        return dto;
    }

    private static ConsultationMessage message(Long id, String content) {
        return ConsultationMessage.builder().id(id).content(content).senderType(1).build();
    }

    private static EmotionDiary diary(Long id) {
        return EmotionDiary.builder().id(id).userId(7L).deleted(false).diaryDate(LocalDate.of(2026, 11, 1))
                .moodScore(7).dominantEmotion("平静").diaryContent("今天散步了").build();
    }
}
