package com.flj.AiService;

import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.AiRequestRecord;
import com.flj.service.AiRequestService;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.AiService.governance.AiProtectionProperties;
import com.flj.config.ChatClientConfig;
import com.flj.service.ConsultationMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PsychologicalSupportServiceTest {
    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 11L;
    private static final String CONVERSATION = "conversation_session_11";
    private PsychologicalSupportService service;
    private ConsultationMessageService messages;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec prompt;
    private ChatClient.StreamResponseSpec stream;
    private ChatMemory memory;
    private ActiveReplyRegistry registry;
    private ConsultationSession session;
    private AiCallGovernor governor;
    private AiCallGovernor.Lease lease;
    private AiRequestService requests;

    @BeforeEach
    void setUp() {
        service = new PsychologicalSupportService();
        messages = mock(ConsultationMessageService.class);
        client = mock(ChatClient.class);
        prompt = mock(ChatClient.ChatClientRequestSpec.class);
        stream = mock(ChatClient.StreamResponseSpec.class);
        memory = spy(MessageWindowChatMemory.builder().maxMessages(30).build());
        registry = new ActiveReplyRegistry();
        session = ConsultationSession.builder().id(SESSION_ID).userId(USER_ID).build();
        // Lifecycle tests deliberately pass governance; dedicated cases below reject or limit it.
        governor = mock(AiCallGovernor.class);
        lease = mock(AiCallGovernor.Lease.class);
        requests = mock(AiRequestService.class);
        when(governor.acquire(USER_ID, "consultation")).thenReturn(lease);
        when(governor.getMaxOutputTokens()).thenReturn(2048);
        when(governor.getMaxReplyBytes()).thenReturn(65536);
        when(governor.getMaxReplyFragments()).thenReturn(4096);
        when(client.prompt()).thenReturn(prompt);
        when(prompt.options(any(OpenAiChatOptions.class))).thenReturn(prompt);
        when(prompt.user(anyString())).thenReturn(prompt);
        when(prompt.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())).thenReturn(prompt);
        when(prompt.stream()).thenReturn(stream);
        when(messages.getRecentMessages(SESSION_ID, 30)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "chatClient", client);
        ReflectionTestUtils.setField(service, "chatMemory", memory);
        ReflectionTestUtils.setField(service, "consultationMessageService", messages);
        ReflectionTestUtils.setField(service, "activeReplyRegistry", registry);
        ReflectionTestUtils.setField(service, "aiCallGovernor", governor);
        ReflectionTestUtils.setField(service, "aiRequestService", requests);
    }

    @Test
    void durableRequestUsesLinkedUserMessageAndAtomicReplyCommit() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(messages.getRecentMessages(SESSION_ID, 30)).thenReturn(List.of(
                ConsultationMessage.builder().id(100L).senderType(1).content("问题").build(),
                ConsultationMessage.builder().id(99L).senderType(2).content("旧回答").build()));
        when(stream.content()).thenReturn(Flux.just("新回答"));

        assertEquals(List.of("新回答"), service.streamPsychologicalChat(session, "问题", USER_ID, handle).collectList().block());
        verify(messages, never()).saveUserMessage(anyLong(), anyString(), any());
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        assertEquals(List.of("旧回答"), memory.get(CONVERSATION).stream().map(Message::getText).toList());
        verify(requests).markDispatched(request);
        verify(requests).finish(request, "SUCCEEDED", null, null, "新回答", true);
        assertEquals("SUCCEEDED", handle.getPersistentRequest().getStatus());
        assertTrue(handle.getPersistentRequest().isSaved());
    }

    @Test
    void durableDispatchedModelErrorIsUnknownAndDoesNotSavePartial() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(stream.content()).thenReturn(Flux.concat(Flux.just("片段"), Flux.error(new IOException("private failure"))));
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast());
        assertEquals("502", failure.getCode()); assertFalse(failure.isSaved());
        verify(requests).finish(eq(request), eq("UNKNOWN"), eq("502"), anyString(), eq("片段"), eq(false));
        assertEquals("UNKNOWN", handle.getPersistentRequest().getStatus());
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void durableTimeoutCommitsPartialAndRetainsUnknownError() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(stream.content()).thenReturn(Flux.concat(Flux.just("片段"), Flux.error(new HttpTimeoutException("private timeout"))));
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast());
        assertEquals("504", failure.getCode()); assertTrue(failure.isSaved());
        verify(requests).finish(eq(request), eq("UNKNOWN"), eq("504"), anyString(), eq("片段"), eq(true));
        assertEquals("UNKNOWN", handle.getPersistentRequest().getStatus());
    }

    @Test
    void durablePreDispatchRejectionIsKnownFailureAndDoesNotCallModel() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(governor.acquire(USER_ID, "consultation")).thenThrow(new AiCallRejectedException("429", "额度不足", 10));
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast());
        assertEquals("429", failure.getCode()); assertEquals("FAILED", handle.getPersistentRequest().getStatus());
        verify(requests).finish(eq(request), eq("FAILED"), eq("429"), anyString(), eq(""), eq(false));
        verify(requests, never()).markDispatched(any()); verify(client, never()).prompt();
    }

    @Test
    void durableReplyWriteFailureFallsBackToUnknownWithoutSuccess() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(stream.content()).thenReturn(Flux.just("完整回答"));
        when(requests.finish(eq(request), eq("SUCCEEDED"), any(), any(), anyString(), eq(true))).thenThrow(new IllegalStateException("private DB"));
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast());
        assertEquals("500", failure.getCode()); assertFalse(failure.isSaved());
        assertEquals("UNKNOWN", handle.getPersistentRequest().getStatus());
        verify(requests).finish(eq(request), eq("UNKNOWN"), eq("500"), anyString(), isNull(), eq(false));
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
    }

    @Test
    void durableCommitAndReconciliationFailuresLeaveSavedUnknown() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        when(stream.content()).thenReturn(Flux.just("完整回答"));
        when(requests.finish(eq(request), anyString(), any(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("unavailable DB"));
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast());
        assertEquals("500", failure.getCode()); assertNull(failure.getSaved());
        assertEquals("UNKNOWN", handle.getPersistentRequest().getStatus());
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void durableDeleteDiscardsPartialAndRecordsDeletedFailure() {
        ActiveReplyHandle handle = claim(); AiRequestRecord request = durable(handle);
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        when(stream.content()).thenReturn(source.asFlux());
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("片段"); registry.stopIfSession(USER_ID, SESSION_ID);
        ReplyFailure failure = assertThrows(ReplyFailure.class, () -> observation.blockLast());
        assertEquals("410", failure.getCode()); assertFalse(failure.isSaved());
        verify(requests).finish(eq(request), eq("FAILED"), eq("410"), anyString(), eq("片段"), eq(false));
    }

    @Test
    void fingerprintChangesWithSessionBodyModelOptionsAndOutputBudget() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        ReflectionTestUtils.setField(service, "openAiChatModel", model);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("model-a").temperature(0.2).build());
        String base = service.requestFingerprint(SESSION_ID, "问题");
        assertEquals(base, service.requestFingerprint(SESSION_ID, "问题"));
        assertNotEquals(base, service.requestFingerprint(SESSION_ID + 1, "问题"));
        assertNotEquals(base, service.requestFingerprint(SESSION_ID, "问题变了"));
        when(governor.getMaxOutputTokens()).thenReturn(1024);
        assertNotEquals(base, service.requestFingerprint(SESSION_ID, "问题"));
        when(governor.getMaxOutputTokens()).thenReturn(2048);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("model-a").temperature(0.7).build());
        assertNotEquals(base, service.requestFingerprint(SESSION_ID, "问题"));
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("model-b").temperature(0.2).build());
        assertNotEquals(base, service.requestFingerprint(SESSION_ID, "问题"));
    }

    private AiRequestRecord durable(ActiveReplyHandle handle) {
        AiRequestRecord request = new AiRequestRecord(); request.setRequestId(handle.getRequestId());
        request.setUserId(USER_ID); request.setSessionId(SESSION_ID); request.setUserMessageId(100L); request.setStatus("RUNNING");
        handle.setPersistentRequest(request);
        when(requests.finish(eq(request), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(call -> {
            request.setStatus(call.getArgument(1)); request.setErrorCode(call.getArgument(2)); request.setErrorMessage(call.getArgument(3));
            String response = call.getArgument(4); request.setSaved(call.getArgument(5, Boolean.class) && response != null && !response.isEmpty());
            return request;
        });
        return request;
    }

    @Test
    void publishedClaimAlreadyContainsClientIdentityAndFingerprint() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ActiveReplyHandle> observed = executor.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                ActiveReplyHandle active;
                do {
                    active = registry.findActive(USER_ID);
                    if (active == null) { Thread.onSpinWait(); }
                } while (active == null && System.nanoTime() < deadline);
                assertNotNull(active);
                assertEquals("client-atomic-id", active.getClientRequestId());
                assertEquals("atomic-fingerprint", active.getRequestFingerprint());
                return active;
            });
            ActiveReplyHandle claimed = registry.tryClaim(USER_ID, SESSION_ID, "client-atomic-id", "atomic-fingerprint");
            assertSame(claimed, observed.get(3, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void disconnectDoesNotCancelModelAndLateObserverGetsOneFullReplay() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean canceled = new AtomicBoolean();
        when(stream.content()).thenReturn(source.asFlux().doOnCancel(() -> canceled.set(true)));
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        Disposable viewer = observation.subscribe();
        source.tryEmitNext("前半");
        viewer.dispose();
        assertFalse(canceled.get());
        verify(lease, never()).close();
        assertSame(handle, registry.findActive(USER_ID));
        source.tryEmitNext("后半");
        source.tryEmitComplete();

        assertEquals(List.of("前半", "后半"), observation.collectList().block(Duration.ofSeconds(2)));
        assertEquals(List.of("前半", "后半"), handle.asFlux().collectList().block(Duration.ofSeconds(2)));
        verify(client, times(1)).prompt();
        verify(messages, times(1)).saveUserMessage(SESSION_ID, "问题", null);
        verify(messages, times(1)).saveAimessage(SESSION_ID, "前半后半", "openai");
        verify(lease).markStarted();
        verify(lease).close();
        assertNull(registry.findActive(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "history", "user-message"})
    void initializationFailureEndsObserversReleasesSlotAndDoesNotStartModel(String stage) {
        RuntimeException privateFailure = new RuntimeException("sensitive backend detail");
        switch (stage) {
            case "memory" -> doThrow(privateFailure).when(memory).get(CONVERSATION);
            case "history" -> when(messages.getRecentMessages(SESSION_ID, 30)).thenThrow(privateFailure);
            case "user-message" -> when(messages.saveUserMessage(SESSION_ID, "问题", null)).thenThrow(privateFailure);
            default -> fail("unexpected stage");
        }
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        ReplyFailure error = assertThrows(ReplyFailure.class, () -> observation.blockLast(Duration.ofSeconds(2)));
        assertEquals("500", error.getCode());
        assertEquals(handle.getRequestId(), error.getRequestId());
        assertFalse(error.getMessage().contains("sensitive"));
        assertFalse(error.isSaved());
        assertNull(registry.findActive(USER_ID));
        verify(client, never()).prompt();
        verify(lease, never()).markStarted();
        verify(lease).close();
        assertNotNull(registry.tryClaim(USER_ID, SESSION_ID));
    }

    @Test
    void stopBeforeServiceStartsSkipsAllInitializationAndModelWork() {
        ActiveReplyHandle handle = claim();
        registry.requestStop(USER_ID, false);
        assertNull(service.streamPsychologicalChat(session, "问题", USER_ID, handle)
                .blockLast(Duration.ofSeconds(2)));
        verifyNoInteractions(messages, client, governor, lease);
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void stopDuringHistoryReadWaitsForInitializationThenNeverStartsModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(messages.getRecentMessages(SESSION_ID, 30)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(proceed.await(3, TimeUnit.SECONDS));
            return List.of();
        });
        ActiveReplyHandle handle = claim();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Flux<String>> started = executor.submit(() ->
                    service.streamPsychologicalChat(session, "问题", USER_ID, handle));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            registry.requestStop(USER_ID, false);
            assertFalse(handle.awaitTermination(0));
            assertSame(handle, registry.findActive(USER_ID));
            assertNull(registry.tryClaim(USER_ID, SESSION_ID));
            verify(lease, never()).close();
            proceed.countDown();
            assertNull(started.get(3, TimeUnit.SECONDS).blockLast(Duration.ofSeconds(2)));
            assertTrue(handle.awaitTermination(0));
            verify(client, never()).prompt();
            verify(messages, never()).saveUserMessage(anyLong(), anyString(), any());
            verify(lease, never()).markStarted();
            verify(lease).close();
            assertNull(registry.findActive(USER_ID));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stopDuringUserMessageWriteDoesNotStartModelOrWriteUserMessageAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(messages.saveUserMessage(SESSION_ID, "问题", null)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(proceed.await(3, TimeUnit.SECONDS));
            return ConsultationMessage.builder().id(1L).build();
        });
        ActiveReplyHandle handle = claim();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Flux<String>> started = executor.submit(() ->
                    service.streamPsychologicalChat(session, "问题", USER_ID, handle));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            registry.requestStop(USER_ID, false);
            proceed.countDown();
            started.get(3, TimeUnit.SECONDS).blockLast(Duration.ofSeconds(2));
            verify(messages, times(1)).saveUserMessage(SESSION_ID, "问题", null);
            verify(client, never()).prompt();
            verify(lease, never()).markStarted();
            verify(lease).close();
            assertNull(registry.findActive(USER_ID));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void explicitStopSavesPartialOnceAndInvalidatesMemory() {
        Sinks.Many<String> source = runningSource();
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("残句");
        memory.add(CONVERSATION, new AssistantMessage("可能不完整的记忆"));
        registry.requestStop(USER_ID, false);
        registry.requestStop(USER_ID, false);
        source.tryEmitComplete();

        assertEquals(List.of("残句"), observation.collectList().block(Duration.ofSeconds(2)));
        verify(messages, times(1)).saveAimessage(SESSION_ID, "残句", "openai");
        verify(lease).close();
        assertTrue(memory.get(CONVERSATION).isEmpty());
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void deleteStopsWithoutSavingPartialAndDoesNotStopAnotherUsersReply() {
        Sinks.Many<String> source = runningSource();
        ActiveReplyHandle handle = claim();
        service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("不保存");
        registry.stopIfSession(8L, SESSION_ID);
        assertSame(handle, registry.findActive(USER_ID));
        registry.stopIfSession(USER_ID, SESSION_ID + 1);
        assertSame(handle, registry.findActive(USER_ID));
        registry.stopIfSession(USER_ID, SESSION_ID);
        handle.asFlux().blockLast(Duration.ofSeconds(2));
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        verify(lease).close();
        assertNull(registry.findActive(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelFailureNeverSavesPartialAndReturnsRedacted502(boolean emitPartial) {
        when(stream.content()).thenReturn((emitPartial ? Flux.just("片段") : Flux.<String>empty())
                .concatWith(Flux.error(new IllegalStateException("private model payload"))));
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        ReplyFailure failure = assertThrows(ReplyFailure.class,
                () -> observation.blockLast(Duration.ofSeconds(2)));
        assertEquals("502", failure.getCode());
        assertFalse(failure.getMessage().contains("private"));
        assertFalse(failure.isSaved());
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        verify(lease).close();
        assertTrue(memory.get(CONVERSATION).isEmpty());
        assertNull(registry.findActive(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistenceFailureEndsWithErrorForCompletionAndStop(boolean stop) {
        Sinks.Many<String> source = runningSource();
        when(messages.saveAimessage(SESSION_ID, "片段", "openai"))
                .thenThrow(new IllegalStateException("private SQL detail"));
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("片段");
        if (stop) { registry.requestStop(USER_ID, false); } else { source.tryEmitComplete(); }
        List<Signal<String>> events = observation.materialize().collectList().block(Duration.ofSeconds(2));
        assertNotNull(events);
        assertEquals("片段", events.get(0).get());
        assertTrue(events.get(1).isOnError());
        ReplyFailure failure = assertInstanceOf(ReplyFailure.class, events.get(1).getThrowable());
        assertEquals("500", failure.getCode());
        assertFalse(failure.isSaved());
        assertFalse(failure.getMessage().contains("SQL"));
        verify(lease).close();
        assertNull(registry.findActive(USER_ID));
        assertTrue(memory.get(CONVERSATION).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absoluteDeadlineCancelsGenerationAndReportsWhetherPartialWasSaved(boolean emitPartial) {
        ReflectionTestUtils.setField(service, "replyTimeoutMillis", 100L);
        AtomicBoolean canceled = new AtomicBoolean();
        when(stream.content()).thenReturn((emitPartial ? Flux.just("超时残句") : Flux.<String>empty())
                .concatWith(Flux.never()).doOnCancel(() -> canceled.set(true)));
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        ReplyFailure failure = assertThrows(ReplyFailure.class,
                () -> observation.blockLast(Duration.ofSeconds(3)));
        assertEquals("504", failure.getCode());
        assertEquals(emitPartial, failure.isSaved());
        assertTrue(canceled.get());
        verify(lease).close();
        if (emitPartial) {
            verify(messages).saveAimessage(SESSION_ID, "超时残句", "openai");
        } else {
            verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        }
        assertNull(registry.findActive(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportTimeoutPreservesPartialWith504AndReleasesActualPermit(boolean emitPartial) {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        limits.setUserRequestsPerMinute(1);
        AiCallGovernor realGovernor = useRealGovernor(limits);
        ResourceAccessException timeout = new ResourceAccessException("private provider response",
                new HttpTimeoutException("private upstream endpoint"));
        when(stream.content()).thenReturn((emitPartial ? Flux.just("网络超时残句") : Flux.<String>empty())
                .concatWith(Flux.error(timeout)));
        ActiveReplyHandle handle = claim();

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast(Duration.ofSeconds(2)));

        assertEquals("504", failure.getCode());
        assertEquals(emitPartial, failure.isSaved());
        assertFalse(failure.getMessage().contains("private"));
        assertEquals(handle.getRequestId(), failure.getRequestId());
        if (emitPartial) {
            verify(messages).saveAimessage(SESSION_ID, "网络超时残句", "openai");
        } else {
            verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        }
        assertTrue(memory.get(CONVERSATION).isEmpty());
        assertNull(registry.findActive(USER_ID));
        assertTrue(handle.awaitTermination(0));
        assertThrows(AiCallRejectedException.class, () -> realGovernor.acquire(USER_ID, "consultation"));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID + 1, "probe")) {
            available.validateInput("probe");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synchronousTransportFailureAfterDispatchUsesTyped502Or504AndReleasesPermit(boolean timeout) {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        AiCallGovernor realGovernor = useRealGovernor(limits);
        IOException cause = timeout ? new HttpTimeoutException("private endpoint") : new IOException("private timeout text");
        when(prompt.stream()).thenThrow(new ResourceAccessException("private timeout provider text", cause));

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, claim()).blockLast(Duration.ofSeconds(2)));

        assertEquals(timeout ? "504" : "502", failure.getCode());
        assertFalse(failure.isSaved());
        assertFalse(failure.getMessage().contains("private"));
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        assertTrue(memory.get(CONVERSATION).isEmpty());
        assertNull(registry.findActive(USER_ID));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID + 1, "probe")) {
            available.validateInput("probe");
        }
    }

    @Test
    void slotIsHeldUntilReplyPersistenceCompletes() throws Exception {
        Sinks.Many<String> source = runningSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(messages.saveAimessage(SESSION_ID, "回复", "openai")).thenAnswer(call -> {
            entered.countDown();
            assertTrue(proceed.await(3, TimeUnit.SECONDS));
            return ConsultationMessage.builder().id(2L).build();
        });
        ActiveReplyHandle handle = claim();
        service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("回复");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = executor.submit(source::tryEmitComplete);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(handle, registry.findActive(USER_ID));
            assertNull(registry.tryClaim(USER_ID, SESSION_ID));
            assertFalse(handle.awaitTermination(0));
            verify(lease, never()).close();
            proceed.countDown();
            completed.get(3, TimeUnit.SECONDS);
            assertNull(registry.findActive(USER_ID));
            handle.asFlux().blockLast(Duration.ofSeconds(2));
            assertTrue(handle.awaitTermination(0));
            verify(lease).close();
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completionRacingStopPersistsAtMostOnceAndNeverReleasesNewHandle() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 12; i++) {
                Sinks.Many<String> source = runningSource();
                ActiveReplyHandle handle = claim();
                service.streamPsychologicalChat(session, "问题", USER_ID, handle);
                source.tryEmitNext("回复");
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> completed = executor.submit(() -> {
                    await(barrier);
                    source.tryEmitComplete();
                });
                Future<?> stopped = executor.submit(() -> {
                    await(barrier);
                    registry.requestStop(USER_ID, false);
                });
                completed.get(3, TimeUnit.SECONDS);
                stopped.get(3, TimeUnit.SECONDS);
                handle.asFlux().blockLast(Duration.ofSeconds(2));
                verify(messages, times(i + 1)).saveAimessage(SESSION_ID, "回复", "openai");
                assertNull(registry.findActive(USER_ID));
                ActiveReplyHandle replacement = claim();
                registry.release(USER_ID, handle);
                assertSame(replacement, registry.findActive(USER_ID));
                registry.release(USER_ID, replacement);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nextTurnRebuildsOrderedDatabaseHistoryAfterAbnormalEnd() {
        Sinks.Many<String> source = runningSource();
        ActiveReplyHandle handle = claim();
        service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("残句");
        registry.requestStop(USER_ID, false);
        when(messages.getRecentMessages(SESSION_ID, 30)).thenReturn(List.of(
                ConsultationMessage.builder().id(2L).senderType(2).content("残句").build(),
                ConsultationMessage.builder().id(1L).senderType(1).content("问题").build()));
        // Simulate an advisor's stale completion after cleanup; the invalidation marker forces reconstruction.
        memory.add(CONVERSATION, new AssistantMessage("错误上下文"));
        when(stream.content()).thenReturn(Flux.empty());
        service.streamPsychologicalChat(session, "继续", USER_ID, claim()).blockLast(Duration.ofSeconds(2));
        List<Message> recovered = memory.get(CONVERSATION);
        assertEquals(2, recovered.size());
        assertInstanceOf(UserMessage.class, recovered.get(0));
        assertEquals("问题", recovered.get(0).getText());
        assertInstanceOf(AssistantMessage.class, recovered.get(1));
        assertEquals("残句", recovered.get(1).getText());
        verify(messages, times(2)).getRecentMessages(SESSION_ID, 30);
    }

    @Test
    void slotIsNeverEvictedByElapsedInitializationTimeAndUsersHaveIndependentSlots() {
        ActiveReplyHandle first = claim();
        ReflectionTestUtils.setField(first, "startedAt", System.currentTimeMillis() - 60_000);
        assertNull(registry.tryClaim(USER_ID, SESSION_ID));
        assertNotNull(registry.tryClaim(USER_ID + 1, SESSION_ID + 1));
        assertSame(first, registry.findActive(USER_ID));
        assertNotEquals(first.getRequestId(), registry.findActive(USER_ID + 1).getRequestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "stop", "timeout", "model-error", "save-error"})
    void realSpringAiMemoryAdvisorMatchesDatabaseEndSemantics(String end) throws Exception {
        ChatModel model = mock(ChatModel.class);
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux());
        ChatClient realClient = ChatClient.builder(model).defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory).scheduler(Schedulers.immediate()).build()).build();
        ReflectionTestUtils.setField(service, "chatClient", realClient);
        if (end.equals("timeout")) {
            ReflectionTestUtils.setField(service, "replyTimeoutMillis", 1000L);
        }
        if (end.equals("save-error")) {
            when(messages.saveAimessage(SESSION_ID, "片段", "openai"))
                    .thenThrow(new IllegalStateException("database unavailable"));
        }
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        CountDownLatch fragmentObserved = new CountDownLatch(1);
        observation.subscribe(fragment -> fragmentObserved.countDown(), error -> { });
        source.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("片段")))));
        assertTrue(fragmentObserved.await(3, TimeUnit.SECONDS));
        assertEquals("问题", memory.get(CONVERSATION).get(0).getText());
        switch (end) {
            case "complete", "save-error" -> source.tryEmitComplete();
            case "stop" -> registry.stopCurrent(USER_ID, false);
            case "model-error" -> source.tryEmitError(new IllegalStateException("model unavailable"));
            case "timeout" -> { /* The short server deadline terminates the pending stream. */ }
            default -> fail("unexpected end");
        }
        if (end.equals("complete") || end.equals("stop")) {
            assertEquals(List.of("片段"), observation.collectList().block(Duration.ofSeconds(3)));
        } else {
            assertThrows(ReplyFailure.class, () -> observation.blockLast(Duration.ofSeconds(3)));
        }
        if (end.equals("complete")) {
            assertEquals(List.of("问题", "片段"), memory.get(CONVERSATION).stream().map(Message::getText).toList());
        } else {
            assertTrue(memory.get(CONVERSATION).isEmpty());
        }
        if (end.equals("complete") || end.equals("stop") || end.equals("timeout")) {
            verify(messages).saveAimessage(SESSION_ID, "片段", "openai");
        } else if (end.equals("model-error")) {
            verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        }
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void controllerFailureBeforeServiceEndsAlreadyAttachedObservers() {
        ActiveReplyHandle handle = claim();
        ReplyFailure failure = new ReplyFailure("404", "会话不存在", handle.getRequestId(), false);
        registry.release(USER_ID, handle);
        handle.failBeforeStart(failure);
        assertSame(failure, assertThrows(ReplyFailure.class,
                () -> handle.asFlux().blockLast(Duration.ofSeconds(2))));
        assertSame(failure, handle.getFailure());
        assertFalse(handle.startIfAllowed(() -> fail("model must not start")));
        assertNotNull(registry.tryClaim(USER_ID, SESSION_ID));
    }

    @Test
    void stopCurrentReturnsTheStoppedHandleWhenItsCompletionAllowsANewClaim() {
        ActiveReplyHandle original = claim();
        original.onStop(() -> {
            registry.release(USER_ID, original);
            assertNotNull(registry.tryClaim(USER_ID, SESSION_ID + 1));
        });
        assertSame(original, registry.stopCurrent(USER_ID, false));
        ActiveReplyHandle replacement = registry.findActive(USER_ID);
        assertNotNull(replacement);
        assertNotSame(original, replacement);
        assertFalse(replacement.isStopRequested());
    }

    @Test
    void terminationWaitRemainsPendingUntilBlockedPersistenceFailureIsReported() throws Exception {
        Sinks.Many<String> source = runningSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        when(messages.saveAimessage(SESSION_ID, "回复", "openai")).thenAnswer(call -> {
            entered.countDown();
            assertTrue(proceed.await(3, TimeUnit.SECONDS));
            throw new IllegalStateException("database unavailable");
        });
        ActiveReplyHandle handle = claim();
        service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("回复");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = executor.submit(source::tryEmitComplete);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            registry.stopCurrent(USER_ID, false);
            assertFalse(handle.awaitTermination(0));
            assertNull(handle.getFailure());
            proceed.countDown();
            completed.get(3, TimeUnit.SECONDS);
            assertTrue(handle.awaitTermination(0));
            assertEquals("500", handle.getFailure().getCode());
            assertFalse(handle.getFailure().isSaved());
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedTerminationWaitRestoresInterruptStatus() {
        ActiveReplyHandle handle = claim();
        try {
            Thread.currentThread().interrupt();
            assertFalse(handle.awaitTermination(100));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void configuredMemoryAdvisorCannotWriteOldUserContentAfterStopAndNextTurn() throws Exception {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt input = call.getArgument(0);
            return input.getUserMessage().getText().equals("继续")
                    ? Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("新回复")))))
                    : Flux.never();
        });
        ChatClientConfig config = spy(new ChatClientConfig());
        doReturn(memory).when(config).chatMemory();
        ReflectionTestUtils.setField(service, "chatClient", config.openAiChatClient(model));

        CountDownLatch advisorReading = new CountDownLatch(1);
        CountDownLatch resumeAdvisor = new CountDownLatch(1);
        CountDownLatch oldUserAdded = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            if (reads.incrementAndGet() == 2) {
                advisorReading.countDown();
                // An in-memory read/add sequence does not abort merely because cancellation interrupts its worker.
                boolean interrupted = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                try {
                    while (resumeAdvisor.getCount() > 0) {
                        try {
                            long remaining = deadline - System.nanoTime();
                            assertTrue(remaining > 0 && resumeAdvisor.await(remaining, TimeUnit.NANOSECONDS));
                        } catch (InterruptedException error) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) { Thread.currentThread().interrupt(); }
                }
            }
            return snapshot;
        }).when(memory).get(CONVERSATION);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            Message message = call.getArgument(1);
            if (message.getText().equals("问题")) { oldUserAdded.countDown(); }
            return result;
        }).when(memory).add(eq(CONVERSATION), any(Message.class));

        ActiveReplyHandle handle = claim();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Flux<String>> generation = executor.submit(() ->
                    service.streamPsychologicalChat(session, "问题", USER_ID, handle));
            assertTrue(advisorReading.await(3, TimeUnit.SECONDS));
            CountDownLatch stopAttempted = new CountDownLatch(1);
            CountDownLatch stopReturned = new CountDownLatch(1);
            Future<?> stopped = executor.submit(() -> {
                stopAttempted.countDown();
                registry.stopCurrent(USER_ID, false);
                stopReturned.countDown();
            });
            assertTrue(stopAttempted.await(3, TimeUnit.SECONDS));
            boolean returnedWhileAdvisorBlocked = stopReturned.await(100, TimeUnit.MILLISECONDS);
            when(messages.getRecentMessages(SESSION_ID, 30)).thenReturn(List.of(
                    ConsultationMessage.builder().id(1L).senderType(1).content("问题").build()));

            if (returnedWhileAdvisorBlocked) {
                // A detached before() callback must not be able to mutate the next generation.
                assertTrue(handle.awaitTermination(0));
                service.streamPsychologicalChat(session, "继续", USER_ID, claim()).blockLast(Duration.ofSeconds(2));
            }
            resumeAdvisor.countDown();
            assertTrue(oldUserAdded.await(3, TimeUnit.SECONDS));
            generation.get(3, TimeUnit.SECONDS);
            stopped.get(3, TimeUnit.SECONDS);
            assertTrue(handle.awaitTermination(0));
            if (!returnedWhileAdvisorBlocked) {
                service.streamPsychologicalChat(session, "继续", USER_ID, claim()).blockLast(Duration.ofSeconds(2));
            }
            assertEquals(List.of("问题", "继续", "新回复"),
                    memory.get(CONVERSATION).stream().map(Message::getText).toList());
        } finally {
            resumeAdvisor.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void governanceValidatesSystemCachedHistoryAndQuestionBeforeSavingOrStarting() {
        memory.add(CONVERSATION, List.of(new UserMessage("历史问题"), new AssistantMessage("历史回答")));
        when(stream.content()).thenReturn(Flux.just("新回答"));

        service.streamPsychologicalChat(session, "新问题", USER_ID, claim()).blockLast(Duration.ofSeconds(2));

        ArgumentCaptor<String[]> input = ArgumentCaptor.forClass(String[].class);
        verify(lease).validateInput(input.capture());
        assertArrayEquals(new String[]{PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT,
                "历史问题", "历史回答", "新问题"}, input.getValue());
        ArgumentCaptor<OpenAiChatOptions> options = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(prompt).options(options.capture());
        assertEquals(2048, options.getValue().getMaxTokens());
        InOrder order = inOrder(lease, messages, client);
        order.verify(lease).validateInput(any(String[].class));
        order.verify(messages).saveUserMessage(SESSION_ID, "新问题", null);
        order.verify(lease).markStarted();
        order.verify(client).prompt();
        verify(lease).close();
    }

    @Test
    void governanceValidatesRestoredDatabaseHistoryInChronologicalOrder() {
        when(messages.getRecentMessages(SESSION_ID, 30)).thenReturn(List.of(
                ConsultationMessage.builder().id(2L).senderType(2).content("数据库回答").build(),
                ConsultationMessage.builder().id(1L).senderType(1).content("数据库问题").build()));
        when(stream.content()).thenReturn(Flux.just("新回答"));

        service.streamPsychologicalChat(session, "新问题", USER_ID, claim()).blockLast(Duration.ofSeconds(2));

        ArgumentCaptor<String[]> input = ArgumentCaptor.forClass(String[].class);
        verify(lease).validateInput(input.capture());
        assertArrayEquals(new String[]{PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT,
                "数据库问题", "数据库回答", "新问题"}, input.getValue());
    }

    @Test
    void inputBudgetRejectionDoesNotSaveQuestionOrStartModelAndReleasesLease() {
        AiCallRejectedException rejection = rejected("400", "输入超出预算");
        doThrow(rejection).when(lease).validateInput(any(String[].class));
        ActiveReplyHandle handle = claim();

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast(Duration.ofSeconds(2)));

        assertEquals("400", failure.getCode());
        assertEquals("输入超出预算", failure.getMessage());
        assertEquals(handle.getRequestId(), failure.getRequestId());
        assertFalse(failure.isSaved());
        verify(messages, never()).saveUserMessage(anyLong(), anyString(), any());
        verify(client, never()).prompt();
        verify(lease, never()).markStarted();
        verify(lease).close();
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void governanceAdmissionRejectionDoesNotReadHistoryOrStartModel() {
        when(governor.acquire(USER_ID, "consultation")).thenThrow(rejected("429", "当前调用已达限制"));
        ActiveReplyHandle handle = claim();

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, handle).blockLast(Duration.ofSeconds(2)));

        assertEquals("429", failure.getCode());
        assertEquals("当前调用已达限制", failure.getMessage());
        verify(memory, never()).get(CONVERSATION);
        verifyNoInteractions(messages, client, lease);
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void utf8OutputBudgetRejectsNextFragmentBeforeReplayAndNeverSavesPartial() {
        when(governor.getMaxReplyBytes()).thenReturn(6);
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean canceled = new AtomicBoolean();
        when(stream.content()).thenReturn(source.asFlux().doOnCancel(() -> canceled.set(true)));
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        source.tryEmitNext("中");
        source.tryEmitNext("文");
        source.tryEmitNext("!");

        List<Signal<String>> replay = observation.materialize().collectList().block(Duration.ofSeconds(2));

        assertNotNull(replay);
        assertEquals(3, replay.size());
        assertEquals("中", replay.get(0).get());
        assertEquals("文", replay.get(1).get());
        ReplyFailure failure = assertInstanceOf(ReplyFailure.class, replay.get(2).getThrowable());
        assertEquals("429", failure.getCode());
        assertFalse(failure.isSaved());
        assertTrue(canceled.get());
        assertTrue(memory.get(CONVERSATION).isEmpty());
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        verify(lease).close();
        assertNull(registry.findActive(USER_ID));
        assertEquals(3, handle.asFlux().materialize().collectList().block(Duration.ofSeconds(2)).size());
    }

    @Test
    void oversizedFirstFragmentIsNotBufferedOrSaved() {
        when(governor.getMaxReplyBytes()).thenReturn(64);
        when(stream.content()).thenReturn(Flux.just("界".repeat(10000)));
        ActiveReplyHandle handle = claim();

        List<Signal<String>> replay = service.streamPsychologicalChat(session, "问题", USER_ID, handle)
                .materialize().collectList().block(Duration.ofSeconds(2));

        assertNotNull(replay);
        assertEquals(1, replay.size());
        assertEquals("429", assertInstanceOf(ReplyFailure.class, replay.get(0).getThrowable()).getCode());
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        verify(lease).close();
    }

    @Test
    void fragmentCountBoundsReplayEvenWhenFragmentsHaveNoContent() {
        when(governor.getMaxReplyFragments()).thenReturn(2);
        when(stream.content()).thenReturn(Flux.just("", "", "third"));

        List<Signal<String>> replay = service.streamPsychologicalChat(session, "问题", USER_ID, claim())
                .materialize().collectList().block(Duration.ofSeconds(2));

        assertNotNull(replay);
        assertEquals(3, replay.size());
        assertEquals("", replay.get(0).get());
        assertEquals("", replay.get(1).get());
        assertEquals("429", assertInstanceOf(ReplyFailure.class, replay.get(2).getThrowable()).getCode());
        verify(messages, never()).saveAimessage(anyLong(), anyString(), anyString());
        verify(lease).close();
    }

    @Test
    void responseExactlyAtByteAndFragmentLimitsCanCompleteNormally() {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        limits.setMaxReplyBytes(6);
        limits.setMaxReplyFragments(1);
        limits.setMaxOutputTokens(23);
        limits.setUserRequestsPerMinute(1);
        AiCallGovernor realGovernor = useRealGovernor(limits);
        when(stream.content()).thenReturn(Flux.just("中文"));

        assertEquals(List.of("中文"), service.streamPsychologicalChat(session, "问题", USER_ID, claim())
                .collectList().block(Duration.ofSeconds(2)));

        verify(messages).saveAimessage(SESSION_ID, "中文", "openai");
        ArgumentCaptor<OpenAiChatOptions> options = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(prompt).options(options.capture());
        assertEquals(23, options.getValue().getMaxTokens());
        assertThrows(AiCallRejectedException.class, () -> realGovernor.acquire(USER_ID, "consultation"));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID + 1, "probe")) {
            available.validateInput("probe");
        }
        assertNull(registry.findActive(USER_ID));
    }

    @Test
    void realGovernorKeepsPermitAcrossViewerDisconnectAndReleasesItAfterStop() {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        AiCallGovernor realGovernor = useRealGovernor(limits);
        Sinks.Many<String> source = runningSource();
        ActiveReplyHandle handle = claim();
        Flux<String> observation = service.streamPsychologicalChat(session, "问题", USER_ID, handle);
        Disposable viewer = observation.subscribe();
        source.tryEmitNext("残句");

        viewer.dispose();

        assertEquals("429", assertThrows(AiCallRejectedException.class,
                () -> realGovernor.acquire(USER_ID + 1, "probe")).getCode());
        assertSame(handle, registry.findActive(USER_ID));
        registry.stopCurrent(USER_ID, false);
        assertEquals(List.of("残句"), observation.collectList().block(Duration.ofSeconds(2)));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID + 1, "probe")) {
            available.validateInput("probe");
        }
        verify(messages).saveAimessage(SESSION_ID, "残句", "openai");
    }

    @ParameterizedTest
    @ValueSource(strings = {"model-error", "init-error", "timeout", "output-bytes", "output-fragments"})
    void realGovernorReleasesCapacityForEveryFailureKind(String end) {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        limits.setMaxReplyBytes(6);
        limits.setMaxReplyFragments(2);
        AiCallGovernor realGovernor = useRealGovernor(limits);
        String expectedCode = switch (end) {
            case "model-error" -> {
                when(stream.content()).thenReturn(Flux.error(new IllegalStateException("private provider detail")));
                yield "502";
            }
            case "init-error" -> {
                doThrow(new IllegalStateException("private database detail")).when(memory).get(CONVERSATION);
                yield "500";
            }
            case "timeout" -> {
                ReflectionTestUtils.setField(service, "replyTimeoutMillis", 30L);
                when(stream.content()).thenReturn(Flux.never());
                yield "504";
            }
            case "output-bytes" -> {
                when(stream.content()).thenReturn(Flux.just("中文!"));
                yield "429";
            }
            case "output-fragments" -> {
                when(stream.content()).thenReturn(Flux.just("a", "b", "c"));
                yield "429";
            }
            default -> throw new IllegalArgumentException(end);
        };

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, claim()).blockLast(Duration.ofSeconds(2)));

        assertEquals(expectedCode, failure.getCode());
        assertNull(registry.findActive(USER_ID));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID + 1, "probe")) {
            available.validateInput("probe");
        }
    }

    @Test
    void realInputBudgetIncludesHistoryAndRefundsUndispatchedQuotaWithoutSavingQuestion() {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(1);
        limits.setUserRequestsPerMinute(1);
        limits.setUserDailyCalls(1);
        limits.setGlobalDailyCalls(1);
        limits.setReservedInputUnits(8);
        limits.setMaxInputUnits(Math.toIntExact(8 + AiCallGovernor.utf8Bytes(
                PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT) + AiCallGovernor.utf8Bytes("问题")));
        AiCallGovernor realGovernor = useRealGovernor(limits);
        memory.add(CONVERSATION, new UserMessage("历史"));

        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                service.streamPsychologicalChat(session, "问题", USER_ID, claim()).blockLast(Duration.ofSeconds(2)));

        assertEquals("400", failure.getCode());
        verify(messages, never()).saveUserMessage(anyLong(), anyString(), any());
        verify(client, never()).prompt();
        assertNull(registry.findActive(USER_ID));
        try (AiCallGovernor.Lease available = realGovernor.acquire(USER_ID, "consultation")) {
            available.validateInput(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT, "问题");
            available.markStarted();
        }
    }

    private AiCallGovernor useRealGovernor(AiProtectionProperties limits) {
        AiCallGovernor realGovernor = new AiCallGovernor(limits,
                Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "aiCallGovernor", realGovernor);
        return realGovernor;
    }

    private AiCallRejectedException rejected(String code, String message) {
        return new AiCallRejectedException(code, message, 0);
    }

    private ActiveReplyHandle claim() {
        ActiveReplyHandle handle = registry.tryClaim(USER_ID, SESSION_ID);
        assertNotNull(handle);
        return handle;
    }

    private Sinks.Many<String> runningSource() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        when(stream.content()).thenReturn(source.asFlux());
        return source;
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(3, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
