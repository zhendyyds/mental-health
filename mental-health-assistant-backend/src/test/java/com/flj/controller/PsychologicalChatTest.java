package com.flj.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.ActiveReplyHandle;
import com.flj.AiService.ActiveReplyRegistry;
import com.flj.AiService.EmotionAnalysisService;
import com.flj.AiService.PsychologicalSupportService;
import com.flj.AiService.ReplyFailure;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.UserLoginResponseDTO;
import com.flj.common.GlobarExceptionHandler;
import com.flj.config.JwtConfig;
import com.flj.config.SecurityConfig;
import com.flj.entity.ConsultationSession;
import com.flj.service.ConsultationMessageService;
import com.flj.service.ConsultationSessionService;
import com.flj.service.UserService;
import com.flj.service.AiRequestService;
import com.flj.entity.AiRequestRecord;
import com.flj.util.JwtTokenUtil;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** MVC and security contracts against real JWT verification, without a database or model. */
class PsychologicalChatTest {
    private static final String ROOT = "/api/psychological-chat";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private PsychologicalSupportService support;
    private ConsultationSessionService sessions;
    private ConsultationMessageService messages;
    private ActiveReplyRegistry registry;
    private AiRequestService requests;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Register existing mocks so Spring does not autowire their production internals.
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("support", mock(PsychologicalSupportService.class));
            factory.registerSingleton("sessions", mock(ConsultationSessionService.class));
            factory.registerSingleton("messages", mock(ConsultationMessageService.class));
            factory.registerSingleton("emotion", mock(EmotionAnalysisService.class));
            factory.registerSingleton("registry", mock(ActiveReplyRegistry.class));
            factory.registerSingleton("users", mock(UserService.class));
            factory.registerSingleton("requests", mock(AiRequestService.class));
        });
        context.register(TestWebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        support = context.getBean(PsychologicalSupportService.class);
        sessions = context.getBean(ConsultationSessionService.class);
        messages = context.getBean(ConsultationMessageService.class);
        registry = context.getBean(ActiveReplyRegistry.class);
        requests = context.getBean(AiRequestService.class);
        when(support.requestFingerprint(anyLong(), anyString())).thenReturn("fingerprint-101");
        when(support.requestModel()).thenReturn("test-model");

        when(context.getBean(UserService.class).getUserById(anyLong())).thenAnswer(invocation ->
                UserLoginResponseDTO.UserDetailResponseDTO.builder()
                        .id(invocation.getArgument(0)).status(1).build());
        when(support.extractSessionId("session_101")).thenReturn(101L);
        when(sessions.findOwnedSession(1L, 101L)).thenReturn(ownedSession(false));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void ownerCanReadMessagesWithStringSuccessCode() throws Exception {
        ConsultationMessageResponseDTO message = new ConsultationMessageResponseDTO();
        message.setId(10L);
        message.setSessionId(101L);
        message.setContent("owner message");
        when(messages.listMessagesBySessionId(101L)).thenReturn(List.of(message));

        JsonNode result = json(perform(get(ROOT + "/sessions/101/messages"), 1L, 1));

        assertEquals("200", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        assertEquals("owner message", result.at("/data/0/content").asText());
        verify(sessions).findOwnedSession(1L, 101L);
        verify(messages).listMessagesBySessionId(101L);
    }

    @Test
    void anotherUserCannotReadMessages() throws Exception {
        JsonNode result = json(perform(get(ROOT + "/sessions/101/messages"), 2L, 1));

        assertEquals("404", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        verify(sessions).findOwnedSession(2L, 101L);
        verifyNoInteractions(messages);
    }

    @Test
    void anotherUserCannotDeleteOrStopOwnersReply() throws Exception {
        JsonNode result = json(perform(delete(ROOT + "/sessions/101"), 2L, 1));

        assertEquals("404", result.path("code").asText());
        verify(sessions).findOwnedSession(2L, 101L);
        verify(sessions, never()).softDeleteSession(anyLong(), anyLong());
        verifyNoInteractions(registry);
    }

    @Test
    void ownerDeleteStopsOnlyTheirSession() throws Exception {
        when(sessions.softDeleteSession(1L, 101L)).thenReturn(true);

        JsonNode result = json(perform(delete(ROOT + "/sessions/101"), 1L, 1));

        assertEquals("200", result.path("code").asText());
        verify(registry).stopIfSession(1L, 101L);
        verify(sessions).softDeleteSession(1L, 101L);
    }

    @Test
    void anotherUserCannotStartGenerationForOwnersSession() throws Exception {
        List<SseEvent> events = events(perform(streamRequest(), 2L, 1));

        assertSingleError(events, "404");
        verifyNoInteractions(registry);
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void deletedSessionCannotStartGeneration() throws Exception {
        when(sessions.findOwnedSession(1L, 101L)).thenReturn(ownedSession(true));

        assertSingleError(events(perform(streamRequest(), 1L, 1)), "404");

        verifyNoInteractions(registry);
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void occupiedSlotRejectsBeforeModelGeneration() throws Exception {
        when(registry.tryClaim(1L, 101L, "client-request-101", "fingerprint-101")).thenReturn(null);

        assertSingleError(events(perform(streamRequest(), 1L, 1)), "409");

        verify(registry).tryClaim(1L, 101L, "client-request-101", "fingerprint-101");
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void missingClientRequestIdIsHttp400BeforeDatabaseOrClaim() throws Exception {
        MvcResult result = perform(post(ROOT + "/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"session_101\",\"userMessage\":\"hello\"}"), 1L, 1);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("400", json(result).path("code").asText());
        verifyNoInteractions(requests, registry);
    }

    @Test
    void invalidClientRequestIdIsHttp400() throws Exception {
        MvcResult result = perform(post(ROOT + "/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"../bad\",\"sessionId\":\"session_101\",\"userMessage\":\"hello\"}"), 1L, 1);
        assertEquals(400, result.getResponse().getStatus());
        verifyNoInteractions(requests, registry);
    }

    @Test
    void runningDuplicateWatchesOriginalWithoutClaimOrNewGeneration() throws Exception {
        AiRequestRecord request = request("RUNNING");
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        ActiveReplyHandle original = mock(ActiveReplyHandle.class);
        when(original.getRequestId()).thenReturn(request.getRequestId());
        when(original.getPersistentRequest()).thenReturn(request);
        when(original.asFlux()).thenReturn(Flux.just("original answer"));
        when(registry.findActive(1L)).thenReturn(original);

        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertEquals(List.of("message", "done"), result.stream().map(SseEvent::name).toList());
        assertEquals("original-request-101", result.get(0).data().at("/data/requestId").asText());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
        verify(requests, never()).create(anyString(), anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void successfulDuplicateReplaysPersistedAnswerEvenWhenOtherReplyOwnsSlot() throws Exception {
        AiRequestRecord request = request("SUCCEEDED"); request.setSaved(true); request.setAiMessageId(90L);
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        when(requests.replay(request)).thenReturn("persisted answer");
        when(registry.findActive(1L)).thenReturn(mock(ActiveReplyHandle.class));
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertEquals(List.of("message", "done"), result.stream().map(SseEvent::name).toList());
        assertEquals("persisted answer", result.get(0).data().at("/data/content").asText());
        assertEquals("original-request-101", result.get(1).data().path("requestId").asText());
        assertTrue(result.get(1).data().path("saved").asBoolean());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void sameClientRequestIdWithDifferentFingerprintConflictsBeforeClaim() throws Exception {
        AiRequestRecord request = request("SUCCEEDED"); request.setRequestFingerprint("changed");
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertSingleError(result, "409");
        assertEquals("CONFLICT", result.get(0).data().at("/data/status").asText());
        verifyNoInteractions(registry);
    }

    @Test
    void failedDuplicateReturnsOriginalFailureWithoutModelRetry() throws Exception {
        AiRequestRecord request = request("FAILED"); request.setErrorCode("429"); request.setErrorMessage("额度不足");
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertSingleError(result, "429");
        assertEquals("FAILED", result.get(0).data().at("/data/status").asText());
        assertEquals("original-request-101", result.get(0).data().at("/data/requestId").asText());
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void unknownDuplicateKeepsTimeoutAndSavedPartialWithoutDone() throws Exception {
        AiRequestRecord request = request("UNKNOWN"); request.setErrorCode("504"); request.setErrorMessage("回复生成超时"); request.setSaved(true);
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertSingleError(result, "409");
        assertEquals("504", result.get(0).data().at("/data/originalErrorCode").asText());
        assertEquals("UNKNOWN", result.get(0).data().at("/data/status").asText());
        assertTrue(result.get(0).data().at("/data/saved").asBoolean());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        verify(requests, never()).replay(any());
    }

    @Test
    void orphanRunningRecordBecomesUnknownWithoutStartingModel() throws Exception {
        AiRequestRecord running = request("RUNNING"); AiRequestRecord unknown = request("UNKNOWN");
        when(requests.find(1L, "client-request-101")).thenReturn(running);
        when(requests.markUnknown(running)).thenReturn(unknown);
        assertSingleError(events(perform(streamRequest(), 1L, 1)), "409");
        verify(requests).markUnknown(running);
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void unavailableLedgerDoesNotClaimRequestFailedOrUnsaved() throws Exception {
        when(requests.find(1L, "client-request-101")).thenThrow(new IllegalStateException("private DB detail"));
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertSingleError(result, "503");
        assertEquals("UNKNOWN", result.get(0).data().at("/data/status").asText());
        assertTrue(result.get(0).data().at("/data/saved").isMissingNode() || result.get(0).data().at("/data/saved").isNull());
        assertFalse(result.toString().contains("private DB detail"));
        verifyNoInteractions(registry);
    }

    @Test
    void ledgerFailureDuringOccupiedSlotRecheckEndsAsUnknownSseError() throws Exception {
        when(requests.find(1L, "client-request-101")).thenReturn(null).thenThrow(new IllegalStateException("private DB detail"));
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertSingleError(result, "503");
        assertEquals("UNKNOWN", result.get(0).data().at("/data/status").asText());
        assertTrue(result.get(0).data().at("/data/saved").isMissingNode() || result.get(0).data().at("/data/saved").isNull());
        verify(registry).tryClaim(1L, 101L, "client-request-101", "fingerprint-101");
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void statusLookupForDeletedOwnedSessionIsHidden() throws Exception {
        when(requests.find(1L, "client-request-101")).thenReturn(request("SUCCEEDED"));
        when(sessions.findOwnedSession(1L, 101L)).thenReturn(ownedSession(true));
        JsonNode result = json(perform(get(ROOT + "/stream/requests/client-request-101"), 1L, 1));
        assertEquals("404", result.path("code").asText());
        verifyNoInteractions(registry);
    }

    @Test
    void duplicateDuringDatabaseCreationWatchesMatchingLocalHandle() throws Exception {
        ActiveReplyHandle original = mock(ActiveReplyHandle.class);
        when(original.getClientRequestId()).thenReturn("client-request-101");
        when(original.getRequestFingerprint()).thenReturn("fingerprint-101");
        when(original.getSessionId()).thenReturn(101L);
        when(original.getRequestId()).thenReturn("pending-request-101");
        when(original.asFlux()).thenReturn(Flux.just("pending original"));
        when(registry.findActive(1L)).thenReturn(original);
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertEquals(List.of("message", "done"), result.stream().map(SseEvent::name).toList());
        assertEquals("pending-request-101", result.get(0).data().at("/data/requestId").asText());
        verify(requests, never()).create(anyString(), anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void successfulEmptyStoppedRequestDoesNotRegenerate() throws Exception {
        AiRequestRecord request = request("SUCCEEDED");
        when(requests.find(1L, "client-request-101")).thenReturn(request);
        when(requests.replay(request)).thenReturn("");
        List<SseEvent> result = events(perform(streamRequest(), 1L, 1));
        assertEquals(List.of("done"), result.stream().map(SseEvent::name).toList());
        assertFalse(result.get(0).data().path("saved").asBoolean());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void statusLookupScopesRequestAndSessionToAuthenticatedUser() throws Exception {
        AiRequestRecord request = request("SUCCEEDED");
        when(requests.find(2L, "client-request-101")).thenReturn(request);
        JsonNode result = json(perform(get(ROOT + "/stream/requests/client-request-101"), 2L, 1));
        assertEquals("404", result.path("code").asText());
        verify(sessions).findOwnedSession(2L, 101L);
        verifyNoInteractions(registry);
    }

    private AiRequestRecord request(String status) {
        AiRequestRecord request = new AiRequestRecord();
        request.setRequestId("original-request-101"); request.setClientRequestId("client-request-101");
        request.setRequestFingerprint("fingerprint-101"); request.setSessionId(101L); request.setUserId(1L); request.setStatus(status);
        return request;
    }

    @Test
    void sessionDeletedBeforeGenerationSubscriptionReleasesSlotWithoutCallingModel() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(sessions.findOwnedSession(1L, 101L))
                .thenReturn(ownedSession(false), ownedSession(true));

        assertSingleError(events(perform(streamRequest(), 1L, 1)), "404");

        verify(registry).release(1L, handle);
        verify(handle).failBeforeStart(any(ReplyFailure.class));
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void successfulStreamHasOrderedMessagesAndOneDone() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(support.streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle)))
                .thenReturn(Flux.just("first", "second"));

        List<SseEvent> events = events(perform(streamRequest(), 1L, 1));

        assertEquals(List.of("message", "message", "done"),
                events.stream().map(SseEvent::name).toList());
        assertEquals("first", events.get(0).data().at("/data/content").asText());
        assertEquals("second", events.get(1).data().at("/data/content").asText());
        assertEquals("200", events.get(0).data().path("code").asText());
        assertTrue(events.get(0).data().path("code").isTextual());
        assertTrue(events.get(2).data().isObject());
        assertEquals("SUCCEEDED", events.get(2).data().path("status").asText());
    }

    @Test
    void modelErrorPreservesFragmentsAndEndsWithUnsavedErrorWithoutDone() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(support.streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle)))
                .thenReturn(Flux.concat(Flux.just("partial"),
                        Flux.error(new IllegalStateException("provider-private-detail"))));

        List<SseEvent> events = events(perform(streamRequest(), 1L, 1));

        assertEquals(List.of("message", "error"), events.stream().map(SseEvent::name).toList());
        assertEquals("partial", events.get(0).data().at("/data/content").asText());
        assertTerminalError(events.get(1));
        assertFalse(events.get(1).data().toString().contains("provider-private-detail"));
    }

    @Test
    void persistenceErrorKeepsRequestIdAndUnsavedStateWithoutDone() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(support.streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle)))
                .thenReturn(Flux.concat(Flux.just("complete text"),
                        Flux.error(new ReplyFailure("500", "回复保存失败", "contract-request-101", false))));

        List<SseEvent> events = events(perform(streamRequest(), 1L, 1));

        assertEquals(List.of("message", "error"), events.stream().map(SseEvent::name).toList());
        assertTerminalError(events.get(1));
        assertEquals("contract-request-101", events.get(1).data().at("/data/requestId").asText());
    }

    @Test
    void synchronousInitializationFailureBecomesUnsavedError() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(support.streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle)))
                .thenThrow(new IllegalStateException("database-private-detail"));

        List<SseEvent> events = events(perform(streamRequest(), 1L, 1));

        assertEquals(1, events.size());
        assertTerminalError(events.get(0));
        assertFalse(events.get(0).data().toString().contains("database-private-detail"));
        verify(registry).release(1L, handle);
        verify(handle).failBeforeStart(any(ReplyFailure.class));
    }

    @Test
    void ownershipLookupFailureAfterClaimTerminatesObserversAndReleasesSlot() throws Exception {
        ActiveReplyHandle handle = claimedHandle();
        when(sessions.findOwnedSession(1L, 101L)).thenReturn(ownedSession(false))
                .thenThrow(new IllegalStateException("lookup-private-detail"));

        List<SseEvent> events = events(perform(streamRequest(), 1L, 1));

        assertEquals(1, events.size());
        assertTerminalError(events.get(0));
        assertFalse(events.get(0).data().toString().contains("lookup-private-detail"));
        verify(registry).release(1L, handle);
        verify(handle).failBeforeStart(any(ReplyFailure.class));
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void stopReturnsSuccessForTheHandleActuallyStoppedWithoutAnotherLookup() throws Exception {
        ActiveReplyHandle stopped = mock(ActiveReplyHandle.class);
        when(stopped.awaitTermination(anyLong())).thenReturn(true);
        when(registry.stopCurrent(1L, false)).thenReturn(stopped);

        JsonNode result = json(perform(post(ROOT + "/stream/stop"), 1L, 1));

        assertEquals("200", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        verify(registry).stopCurrent(1L, false);
        verify(registry, never()).findActive(anyLong());
        verify(registry, never()).requestStop(anyLong(), anyBoolean());
        verify(stopped).getFailure();
    }

    @Test
    void stopStillInProgressReturnsConflictWithoutPretendingSavedOrReleasingSlot() throws Exception {
        ActiveReplyHandle stopped = mock(ActiveReplyHandle.class);
        when(stopped.getRequestId()).thenReturn("stopping-request-101");
        when(stopped.awaitTermination(anyLong())).thenReturn(false);
        when(registry.stopCurrent(1L, false)).thenReturn(stopped);

        JsonNode result = json(perform(post(ROOT + "/stream/stop"), 1L, 1));

        assertEquals("409", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        assertEquals("stopping-request-101", result.at("/data/requestId").asText());
        assertTrue(result.at("/data/stopping").asBoolean());
        assertFalse(result.path("data").has("saved"));
        verify(stopped).awaitTermination(3000L);
        verify(stopped, never()).getFailure();
        verify(registry, never()).release(anyLong(), any());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        verifyNoInteractions(support);
    }

    @Test
    void stopWithoutActiveReplyReturnsStringNotFoundCodeForCurrentUser() throws Exception {
        JsonNode result = json(perform(post(ROOT + "/stream/stop"), 2L, 1));

        assertEquals("404", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        verify(registry).stopCurrent(2L, false);
        verify(registry, never()).findActive(anyLong());
    }

    @Test
    void stopPersistenceFailureReturnsFailureMetadataInsteadOfSuccess() throws Exception {
        ActiveReplyHandle stopped = mock(ActiveReplyHandle.class);
        ReplyFailure failure = new ReplyFailure("500", "回复保存失败", "failed-stop-request", false);
        when(stopped.getFailure()).thenReturn(failure);
        when(stopped.awaitTermination(anyLong())).thenReturn(true);
        when(registry.stopCurrent(1L, false)).thenReturn(stopped);

        JsonNode result = json(perform(post(ROOT + "/stream/stop"), 1L, 1));

        assertEquals("500", result.path("code").asText());
        assertTrue(result.path("code").isTextual());
        assertEquals("回复保存失败", result.path("msg").asText());
        assertEquals("failed-stop-request", result.at("/data/requestId").asText());
        assertFalse(result.at("/data/saved").asBoolean(true));
        verify(registry).stopCurrent(1L, false);
        verify(registry, never()).findActive(anyLong());
    }

    @Test
    void activeStateUsesEachAuthenticatedUsersRegistryEntry() throws Exception {
        ActiveReplyHandle first = mock(ActiveReplyHandle.class);
        ActiveReplyHandle second = mock(ActiveReplyHandle.class);
        when(first.getSessionId()).thenReturn(101L);
        when(second.getSessionId()).thenReturn(202L);
        when(registry.findActive(1L)).thenReturn(first);
        when(registry.findActive(2L)).thenReturn(second);

        assertEquals(101L, json(perform(get(ROOT + "/stream/active"), 1L, 1))
                .at("/data/sessionId").asLong());
        assertEquals(202L, json(perform(get(ROOT + "/stream/active"), 2L, 1))
                .at("/data/sessionId").asLong());
        verify(registry).findActive(1L);
        verify(registry).findActive(2L);
    }

    @Test
    void anotherUserCannotAttachToOwnersReply() throws Exception {
        ActiveReplyHandle owner = mock(ActiveReplyHandle.class);
        when(owner.getSessionId()).thenReturn(101L);
        when(registry.findActive(1L)).thenReturn(owner);

        assertSingleError(events(perform(post(ROOT + "/stream/attach/101"), 2L, 1)), "404");

        verify(sessions).findOwnedSession(2L, 101L);
        verify(registry, never()).findActive(2L);
        verify(owner, never()).asFlux();
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void ownerAttachReplaysExistingStreamWithoutStartingModel() throws Exception {
        ActiveReplyHandle handle = mock(ActiveReplyHandle.class);
        when(handle.getSessionId()).thenReturn(101L);
        when(handle.getRequestId()).thenReturn("contract-request-101");
        when(handle.asFlux()).thenReturn(Flux.just("replay"));
        when(registry.findActive(1L)).thenReturn(handle);

        List<SseEvent> events = events(perform(post(ROOT + "/stream/attach/101"), 1L, 1));

        assertEquals(List.of("message", "done"), events.stream().map(SseEvent::name).toList());
        assertEquals("replay", events.get(0).data().at("/data/content").asText());
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
        verify(registry, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void attachWritesCommentHeartbeatAndStopsAfterDoneWithoutRestartingModel() throws Exception {
        ReflectionTestUtils.setField(context.getBean(PsychologicalChat.class), "heartbeatInterval", Duration.ofMillis(30));
        ActiveReplyHandle handle = mock(ActiveReplyHandle.class);
        when(handle.getSessionId()).thenReturn(101L);
        when(handle.getRequestId()).thenReturn("heartbeat-attach-101");
        when(handle.asFlux()).thenReturn(Flux.concat(Flux.just("replay"), Mono.delay(Duration.ofMillis(250)).thenReturn("tail")));
        when(registry.findActive(1L)).thenReturn(handle);

        MvcResult result = perform(post(ROOT + "/stream/attach/101"), 1L, 1);
        String wire = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(wire.matches("(?s).*:\\s*heartbeat\\r?\\n.*"));
        assertEquals(List.of("message", "message", "done"), events(result).stream().map(SseEvent::name).toList());
        assertTrue(wire.lastIndexOf("heartbeat\n") < wire.lastIndexOf("event:done"));
        verify(handle).asFlux();
        verify(support, never()).streamPsychologicalChat(any(), anyString(), anyLong(), any());
    }

    @Test
    void generationObservationStopsHeartbeatAfterItsSingleErrorFrame() throws Exception {
        ReflectionTestUtils.setField(context.getBean(PsychologicalChat.class), "heartbeatInterval", Duration.ofMillis(30));
        ActiveReplyHandle handle = claimedHandle();
        Flux<String> failedReply = Flux.concat(Flux.just("partial"), Mono.delay(Duration.ofMillis(250))
                .flatMap(tick -> Mono.error(new ReplyFailure("502", "回复暂不可用", handle.getRequestId(), false))));
        when(support.streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle))).thenReturn(failedReply);

        MvcResult result = perform(streamRequest(), 1L, 1);
        String wire = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(wire.matches("(?s).*:\\s*heartbeat\\r?\\n.*"));
        assertEquals(List.of("message", "error"), events(result).stream().map(SseEvent::name).toList());
        assertTrue(wire.lastIndexOf("heartbeat\n") < wire.lastIndexOf("event:error"));
        verify(support).streamPsychologicalChat(any(), eq("hello"), eq(1L), eq(handle));
    }

    @Test
    void ordinaryUserCannotAccessAdminRouteWhileAdminCan() throws Exception {
        assertEquals(403, perform(get("/api/admin/contract-probe"), 1L, 1).getResponse().getStatus());
        assertEquals(200, perform(get("/api/admin/contract-probe"), 2L, 2).getResponse().getStatus());
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeController() throws Exception {
        MvcResult result = mvc.perform(get(ROOT + "/stream/active")).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertTrue(json(result).path("code").isTextual());
        verifyNoInteractions(registry);
    }

    private ConsultationSession ownedSession(boolean deleted) {
        return ConsultationSession.builder().id(101L).userId(1L).deleted(deleted).build();
    }

    private ActiveReplyHandle claimedHandle() {
        ActiveReplyHandle handle = mock(ActiveReplyHandle.class);
        AtomicReference<ReplyFailure> failure = new AtomicReference<>();
        when(handle.getSessionId()).thenReturn(101L);
        when(handle.getRequestId()).thenReturn("contract-request-101");
        when(handle.asFlux()).thenAnswer(invocation -> failure.get() == null
                ? Flux.never() : Flux.error(failure.get()));
        doAnswer(invocation -> {
            failure.set(invocation.getArgument(0));
            return null;
        }).when(handle).failBeforeStart(any(ReplyFailure.class));
        when(registry.tryClaim(1L, 101L, "client-request-101", "fingerprint-101")).thenReturn(handle);
        return handle;
    }

    private MockHttpServletRequestBuilder streamRequest() {
        return post(ROOT + "/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"client-request-101\",\"sessionId\":\"session_101\",\"userMessage\":\"hello\"}");
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Long userId, int role) throws Exception {
        request.header("token", JwtTokenUtil.generateToken(userId, "test-user-" + userId, role));
        MvcResult result = mvc.perform(request).andReturn();
        return result.getRequest().isAsyncStarted()
                ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<SseEvent> events(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus());
        assertNotNull(result.getResponse().getContentType());
        assertTrue(result.getResponse().getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        List<SseEvent> events = new ArrayList<>();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String frame : body.split("\\r?\\n\\r?\\n")) {
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\\r?\\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (event != null) {
                events.add(new SseEvent(event, JSON.readTree(data.toString())));
            }
        }
        assertFalse(events.isEmpty(), "Expected at least one SSE event");
        return events;
    }

    private void assertSingleError(List<SseEvent> events, String code) {
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).name());
        assertEquals(code, events.get(0).data().path("code").asText());
        assertTrue(events.get(0).data().path("code").isTextual());
    }

    private void assertTerminalError(SseEvent event) {
        assertEquals("error", event.name());
        assertTrue(event.data().path("code").isTextual());
        assertFalse(event.data().at("/data/saved").asBoolean(true));
        assertRequestId(event.data());
    }

    private void assertRequestId(JsonNode payload) {
        assertTrue(payload.at("/data/requestId").isTextual());
        assertFalse(payload.at("/data/requestId").asText().isBlank());
    }

    private record SseEvent(String name, JsonNode data) { }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestWebConfig implements WebMvcConfigurer {
        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            // Finite mock SSE sources drain before MockHttpServletResponse's non-thread-safe header map is touched by filter unwind.
            configurer.setTaskExecutor(new ConcurrentTaskExecutor(Runnable::run));
        }
        @Bean PsychologicalChat psychologicalChat() { return new PsychologicalChat(); }
        @Bean JwtTokenUtil jwtTokenUtil() { return new JwtTokenUtil(); }
        @Bean GlobarExceptionHandler exceptionHandler() { return new GlobarExceptionHandler(); }
        @Bean AdminProbeController adminProbeController() { return new AdminProbeController(); }

        @Bean
        JwtConfig jwtConfig() {
            JwtConfig config = new JwtConfig();
            config.setSecret("contract-test-secret-only-do-not-use-in-deployment");
            config.setExpiration(60_000);
            return config;
        }
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/admin/contract-probe")
        String probe() { return "admin"; }
    }
}
