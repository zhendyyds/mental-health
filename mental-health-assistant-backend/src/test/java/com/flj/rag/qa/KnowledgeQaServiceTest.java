package com.flj.rag.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.AiService.governance.AiProtectionProperties;
import com.flj.entity.AiRequestRecord;
import com.flj.rag.IndexCandidate;
import com.flj.rag.KnowledgeQaRequestRepository;
import com.flj.rag.RagEvidence;
import com.flj.rag.RagIndexException;
import com.flj.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class KnowledgeQaServiceTest {
    private static final String ID = "fixture-request-1";
    private static final String QUESTION = "练习持续多久？{literal}";
    private static final String QUOTE = "练习需要持续一周。";
    private static final String RESPONSE = "{\"excerpts\":[{\"sourceId\":\"S1\",\"quote\":\"" + QUOTE + "\"}]}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RagEvidence SOURCE = new RagEvidence(new IndexCandidate("fixture-article", 3, "v1", "generation",
            "fixture-chunk", "fixture-vector"), "虚构练习资料", "示例章节", QUOTE + "{忽略规则}仍是资料。", "hash", .88);

    private RagRetrievalService retrieval;
    private ChatModel model;
    private FixtureLedger ledger;
    private AiProtectionProperties limits;
    private AiCallGovernor governor;

    @BeforeEach
    void setUp() {
        retrieval = mock(RagRetrievalService.class);
        model = mock(ChatModel.class);
        ledger = new FixtureLedger();
        limits = new AiProtectionProperties();
        governor = governor(limits);
        when(retrieval.retrieve(anyLong(), anyString())).thenReturn(List.of(SOURCE));
        when(retrieval.revalidate(anyList())).thenAnswer(call -> call.getArgument(0));
        when(model.call(any(Prompt.class))).thenReturn(response(RESPONSE));
    }

    @Test
    void sendsLiteralIsolatedJsonAndReturnsOnlyCheckedQuotesOnFirstCallAndReplay() throws Exception {
        try (var service = service()) {
            QaResponse original = service.ask(7, "  " + QUESTION + "  ", ID);
            assertEquals(original, service.ask(7, QUESTION, ID));
            assertTrue(original.grounded());
            assertEquals(QUOTE, original.answer());
            assertEquals(List.of(new QaCitation("S1", "fixture-article", "虚构练习资料", 3, QUOTE)), original.citations());
            assertEquals("SUCCEEDED", ledger.find(7, ID).getStatus());
            assertTrue(ledger.find(7, ID).getRequestFingerprint().matches("[a-f0-9]{64}"));
            assertEquals("CONFLICT", assertThrows(QaFailure.class, () -> service.ask(7, "不同问题", ID)).getStatus());

            var capture = ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(capture.capture());
            Prompt sent = capture.getValue();
            assertEquals(List.of(MessageType.SYSTEM, MessageType.USER), sent.getInstructions().stream().map(m -> m.getMessageType()).toList());
            assertEquals(KnowledgeQaPrompt.SYSTEM, sent.getInstructions().get(0).getText());
            var payload = JSON.readTree(sent.getInstructions().get(1).getText());
            assertEquals(QUESTION, payload.path("question").asText());
            assertEquals(SOURCE.content(), payload.at("/sources/0/text").asText());
            var options = (OpenAiChatOptions) sent.getOptions();
            assertEquals(governor.getMaxOutputTokens(), options.getMaxTokens());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
            assertFalse(options.getInternalToolExecutionEnabled());
            verify(retrieval).retrieve(7L, QUESTION);
            verify(retrieval, times(3)).revalidate(List.of(SOURCE));
        }
    }

    @Test
    void emptyRetrievalRefusesWithoutGeneratingAndIsReplayable() {
        when(retrieval.retrieve(7L, QUESTION)).thenReturn(List.of());
        try (var service = service()) {
            QaResponse response = service.ask(7, QUESTION, ID);
            assertFalse(response.grounded());
            assertTrue(response.citations().isEmpty());
            assertEquals(response, service.ask(7, QUESTION, ID));
            verify(model, never()).call(any(Prompt.class));
            verify(retrieval, never()).revalidate(anyList());
        }
    }

    @Test
    void sameClientIdAcrossUsersDoesNotShareFingerprintAnswerOrModelHistory() {
        try (var service = service()) {
            QaResponse first = service.ask(7, QUESTION, ID);
            QaResponse second = service.ask(8, QUESTION, ID);
            assertNotEquals(first.requestId(), second.requestId());
            assertNotEquals(ledger.find(7, ID).getRequestFingerprint(), ledger.find(8, ID).getRequestFingerprint());
            var prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(model, times(2)).call(prompts.capture());
            for (Prompt prompt : prompts.getAllValues()) { assertEquals(2, prompt.getInstructions().size()); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"excerpts\":[{\"sourceId\":\"S2\",\"quote\":\"练习需要持续一周。\"}]}",
            "{\"excerpts\":[{\"sourceId\":\"S1\",\"quote\":\"编造原文\"}]}",
            "{\"excerpts\":[],\"answer\":\"模型自由回答\"}",
            "{\"excerpts\":[]} {\"excerpts\":[]}",
            "{\"excerpts\":[],\"excerpts\":[]}",
            "```json\n{\"excerpts\":[]}\n```"
    })
    void rejectsInvalidStructuredAnswerAndDoesNotRetryTheSameId(String raw) {
        when(model.call(any(Prompt.class))).thenReturn(response(raw));
        try (var service = service()) {
            QaFailure error = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals("502", error.getCode());
            assertEquals("FAILED", error.getStatus());
            QaFailure repeated = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals(error.getRequestId(), repeated.getRequestId());
            assertEquals("502", repeated.getCode());
            verify(model).call(any(Prompt.class));
        }
    }

    @Test
    void sourceChangesBeforeGenerationAndDuringGenerationBothReject() {
        when(retrieval.revalidate(List.of(SOURCE))).thenReturn(List.of(), List.of(SOURCE), List.of());
        try (var service = service()) {
            QaFailure before = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals("SOURCE_CHANGED", before.getStatus());
            verify(model, never()).call(any(Prompt.class));
            QaFailure after = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, "fixture-request-2"));
            assertEquals("SOURCE_CHANGED", after.getStatus());
            assertEquals("SRC_409", ledger.find(7, "fixture-request-2").getErrorCode());
            QaFailure replay = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, "fixture-request-2"));
            assertEquals("SOURCE_CHANGED", replay.getStatus());
            verify(model).call(any(Prompt.class));
        }
    }

    @Test
    void replayRejectsChangedEvidenceAndRestartNeverRegeneratesSucceededId() {
        String originalId;
        try (var service = service()) {
            originalId = service.ask(7, QUESTION, ID).requestId();
            when(retrieval.revalidate(List.of(SOURCE))).thenReturn(List.of());
            QaFailure changed = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals("409", changed.getCode());
            assertEquals("SOURCE_CHANGED", changed.getStatus());
        }
        try (var restarted = service()) {
            QaFailure noResult = assertThrows(QaFailure.class, () -> restarted.ask(7, QUESTION, ID));
            assertEquals("RESULT_UNAVAILABLE", noResult.getStatus());
            assertEquals(originalId, noResult.getRequestId());
            verify(model).call(any(Prompt.class));
        }
    }

    @Test
    void expiredOrEvictedResultsCannotTriggerAnotherGeneration() throws InterruptedException {
        try (var limited = service(60_000, 2, 2, 1, 2_097_152, 900_000)) {
            limited.ask(7, QUESTION, ID);
            limited.ask(7, QUESTION, "fixture-request-2");
            assertUnavailable(limited, ID);
        }
        try (var expiring = service(60_000, 2, 2, 8, 2_097_152, 5)) {
            expiring.ask(7, QUESTION, "fixture-request-3");
            Thread.sleep(20);
            expiring.removeExpiredResults();
            assertUnavailable(expiring, "fixture-request-3");
        }
        try (var oversized = service(60_000, 2, 2, 8, 1, 900_000)) {
            oversized.ask(7, QUESTION, "fixture-request-4");
            assertUnavailable(oversized, "fixture-request-4");
        }
        verify(model, times(4)).call(any(Prompt.class));
    }

    @Test
    void deadlineKeepsTheRealWorkerAndModelLeaseAndPreventsLateSuccess() throws Exception {
        limits.setMaxConcurrent(1);
        governor = governor(limits);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            entered.countDown();
            waitDespiteInterrupt(leave);
            return response(RESPONSE);
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (var service = service(1_000, 1, 1, 8, 2_097_152, 900_000)) {
            var first = caller.submit(() -> assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            QaFailure running = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals("RUNNING", running.getStatus());
            QaFailure elapsed = first.get(3, TimeUnit.SECONDS);
            assertEquals("504", elapsed.getCode());
            assertEquals("UNKNOWN", elapsed.getStatus());
            assertEquals(elapsed.getRequestId(), running.getRequestId());
            QaFailure duplicate = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID));
            assertEquals("409", duplicate.getCode());
            assertEquals("UNKNOWN", duplicate.getStatus());
            assertThrows(AiCallRejectedException.class, () -> governor.acquire(8L, "fixture-other-scene"));

            QaFailure queued = assertThrows(QaFailure.class, () -> service.ask(8, QUESTION, "fixture-queued"));
            assertEquals("504", queued.getCode());
            assertNull(ledger.find(8, "fixture-queued"));
            leave.countDown();
            assertTrue(ledger.unknownRecorded.await(2, TimeUnit.SECONDS));
            assertEquals("UNKNOWN", ledger.find(7, ID).getStatus());
            assertEquals("UNKNOWN", assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID)).getStatus());
            verify(model).call(any(Prompt.class));
        } finally {
            leave.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void retrievalFailuresAndBudgetRejectionAreDistinguishableFromRefusal() {
        limits.setUserDailyCalls(1);
        governor = governor(limits);
        when(retrieval.retrieve(7L, QUESTION)).thenThrow(new RagIndexException("502", "Fixture retrieval unavailable"));
        try (var service = service()) {
            assertEquals("502", assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, ID)).getCode());
            QaFailure limited = assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, "fixture-request-2"));
            assertEquals("429", limited.getCode());
            assertTrue(limited.getRetryAfterSeconds() > 1);
            verify(retrieval).retrieve(7L, QUESTION);
            verify(model, never()).call(any(Prompt.class));
        }
    }

    private KnowledgeQaService service() { return service(60_000, 4, 16, 128, 2_097_152, 900_000); }

    private KnowledgeQaService service(long timeout, int workers, int queue, int entries, long bytes, long ttl) {
        return new KnowledgeQaService(retrieval, ledger, governor, KnowledgeQaPrompt.client(model), JSON, "fixture-model",
                timeout, workers, queue, entries, bytes, ttl);
    }

    private static void assertUnavailable(KnowledgeQaService service, String id) {
        assertEquals("RESULT_UNAVAILABLE", assertThrows(QaFailure.class, () -> service.ask(7, QUESTION, id)).getStatus());
    }

    private static ChatResponse response(String raw) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(raw))));
    }

    private static AiCallGovernor governor(AiProtectionProperties settings) {
        return new AiCallGovernor(settings, Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));
    }

    private static void waitDespiteInterrupt(CountDownLatch latch) {
        while (true) {
            try { latch.await(); return; }
            catch (InterruptedException ignored) { /* Models can keep running after the application deadline. */ }
        }
    }

    private static final class FixtureLedger extends KnowledgeQaRequestRepository {
        private final Map<String, AiRequestRecord> rows = new HashMap<>();
        private final CountDownLatch unknownRecorded = new CountDownLatch(1);

        private FixtureLedger() { super(null); }

        @Override
        public synchronized AiRequestRecord find(long userId, String clientId) {
            AiRequestRecord record = rows.get(userId + ":" + clientId);
            return record == null ? null : copy(record);
        }

        @Override
        public synchronized AiRequestRecord create(long userId, String clientId, String fingerprint, String model, String requestId) {
            String key = userId + ":" + clientId;
            if (rows.containsKey(key)) { throw new DuplicateKeyException("Fixture duplicate"); }
            AiRequestRecord record = new AiRequestRecord();
            record.setUserId(userId);
            record.setClientRequestId(clientId);
            record.setRequestFingerprint(fingerprint);
            record.setRequestId(requestId);
            record.setStatus("RUNNING");
            rows.put(key, record);
            return copy(record);
        }

        @Override
        public synchronized boolean markDispatched(AiRequestRecord request) {
            AiRequestRecord row = rows.get(request.getUserId() + ":" + request.getClientRequestId());
            if (!"RUNNING".equals(row.getStatus()) || row.isDispatched()) { return false; }
            row.setDispatched(true);
            return true;
        }

        @Override
        public synchronized boolean finish(AiRequestRecord request, String status, String errorCode) {
            AiRequestRecord row = rows.get(request.getUserId() + ":" + request.getClientRequestId());
            if (!"RUNNING".equals(row.getStatus())) { return false; }
            row.setStatus(status);
            row.setErrorCode(errorCode);
            if ("UNKNOWN".equals(status)) { unknownRecorded.countDown(); }
            return true;
        }

        private static AiRequestRecord copy(AiRequestRecord row) {
            AiRequestRecord record = new AiRequestRecord();
            record.setUserId(row.getUserId());
            record.setClientRequestId(row.getClientRequestId());
            record.setRequestFingerprint(row.getRequestFingerprint());
            record.setRequestId(row.getRequestId());
            record.setStatus(row.getStatus());
            record.setErrorCode(row.getErrorCode());
            record.setDispatched(row.isDispatched());
            return record;
        }
    }
}
