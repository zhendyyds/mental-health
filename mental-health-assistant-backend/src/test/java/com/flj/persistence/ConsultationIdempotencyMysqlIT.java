package com.flj.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.ActiveReplyRegistry;
import com.flj.AiService.PsychologicalSupportService;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiProtectionProperties;
import com.flj.DTO.command.ConsultationStreamDTO;
import com.flj.config.JwtConfig;
import com.flj.controller.PsychologicalChat;
import com.flj.mapper.ConsultationMessageMapper;
import com.flj.mapper.ConsultationSessionMapper;
import com.flj.service.ConsultationMessageService;
import com.flj.service.ConsultationSessionService;
import com.flj.util.JwtTokenUtil;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Production controller, task, advisor, mappers and transactions; only the remote model is a Stub. */
class ConsultationIdempotencyMysqlIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MysqlVerification db;
    private PsychologicalChat controller;
    private ActiveReplyRegistry registry;
    private ChatMemory memory;
    private GenericApplicationContext jwtContext;
    private final List<Sinks.Many<ChatResponse>> modelStreams = new ArrayList<>();
    private final List<Prompt> modelInputs = new ArrayList<>();

    @BeforeEach
    void fixture() {
        db = new MysqlVerification();
        db.reset();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("idempotency-real-mysql", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(ConsultationMessageMapper.class);
        configuration.addMapper(ConsultationSessionMapper.class);
        SqlSessionTemplate sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        ConsultationMessageService messages = new ConsultationMessageService();
        ReflectionTestUtils.setField(messages, "consultationMessageMapper", sql.getMapper(ConsultationMessageMapper.class));
        ReflectionTestUtils.setField(messages, "aiRequestService", db.requests);
        ConsultationSessionService sessions = new ConsultationSessionService();
        ReflectionTestUtils.setField(sessions, "consultationSessionMapper", sql.getMapper(ConsultationSessionMapper.class));
        ReflectionTestUtils.setField(sessions, "consultationMessageService", messages);

        registry = new ActiveReplyRegistry();
        memory = spy(MessageWindowChatMemory.builder().maxMessages(30).build());
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("fictional-model").temperature(0.3).build());
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
            modelInputs.add(invocation.getArgument(0));
            modelStreams.add(source);
            return source.asFlux();
        });
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).scheduler(Schedulers.immediate()).build())
                .build();
        PsychologicalSupportService support = new PsychologicalSupportService();
        ReflectionTestUtils.setField(support, "chatClient", client);
        ReflectionTestUtils.setField(support, "chatMemory", memory);
        ReflectionTestUtils.setField(support, "consultationMessageService", messages);
        ReflectionTestUtils.setField(support, "consultationSessionService", sessions);
        ReflectionTestUtils.setField(support, "activeReplyRegistry", registry);
        ReflectionTestUtils.setField(support, "aiCallGovernor", new AiCallGovernor(new AiProtectionProperties()));
        ReflectionTestUtils.setField(support, "aiRequestService", db.requests);
        ReflectionTestUtils.setField(support, "openAiChatModel", model);
        controller = new PsychologicalChat();
        ReflectionTestUtils.setField(controller, "psychologicalSupportService", support);
        ReflectionTestUtils.setField(controller, "consultationMessageService", messages);
        ReflectionTestUtils.setField(controller, "consultationSessionService", sessions);
        ReflectionTestUtils.setField(controller, "activeReplyRegistry", registry);
        ReflectionTestUtils.setField(controller, "aiRequestService", db.requests);

        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("fictional-batch3-integration-signing-key");
        jwt.setExpiration(60000);
        jwtContext = new GenericApplicationContext();
        jwtContext.getBeanFactory().registerSingleton("jwtConfig", jwt);
        jwtContext.refresh();
        new JwtTokenUtil().setApplicationContext(jwtContext);
        user(101L);
    }

    @AfterEach
    void close() {
        if (registry != null) {
            registry.stopCurrent(101L, true);
            registry.stopCurrent(102L, true);
        }
        RequestContextHolder.resetRequestAttributes();
        if (jwtContext != null) { jwtContext.close(); }
    }

    @Test
    void runningDuplicateDisconnectAndSuccessReplayUseOneModelAndOneMessagePair() {
        ConsultationStreamDTO input = input("wire-repeat-id", 201L, "IT fictional question");
        List<ServerSentEvent<String>> first = new CopyOnWriteArrayList<>();
        Disposable viewer = controller.streamChat(input).subscribe(first::add);
        assertEquals(1, modelStreams.size());
        String requestId = db.requests.find(101L, input.getClientRequestId()).getRequestId();
        emit(0, "前半");
        List<ServerSentEvent<String>> duplicate = new CopyOnWriteArrayList<>();
        controller.streamChat(input).subscribe(duplicate::add);
        viewer.dispose();
        assertNotNull(registry.findActive(101L));
        emit(0, "后半");
        complete(0, 101L);

        assertEquals("前半后半", content(duplicate));
        assertEquals("done", duplicate.get(duplicate.size() - 1).event());
        List<ServerSentEvent<String>> replay = collect(input);
        assertEquals("前半后半", content(replay));
        assertEquals(2, replay.size());
        assertEquals(requestId, data(replay.get(0)).at("/data/requestId").asText());
        assertEquals(1, modelStreams.size());
        assertEquals(2, db.count("consultation_message"));
        assertEquals("SUCCEEDED", db.requests.find(101L, input.getClientRequestId()).getStatus());
        assertNull(registry.findActive(101L));
        assertEquals(1, modelInputs.get(0).getInstructions().stream()
                .filter(message -> message instanceof UserMessage && "IT fictional question".equals(message.getText())).count());
        controller.streamChat(input("wire-another-active", 201L, "another explicit question")).subscribe();
        assertEquals(2, modelStreams.size());
        assertEquals("前半后半", content(collect(input)), "An unrelated active slot must not hide an earlier result");
        assertEquals(2, modelStreams.size());
        assertEquals(3, db.count("consultation_message"));
    }

    @Test
    void changedFingerprintConflictsAndTheSameClientIdIsIsolatedBetweenUsers() {
        ConsultationStreamDTO owner = input("wire-scoped-id", 201L, "owner fictional question");
        controller.streamChat(owner).subscribe();
        List<ServerSentEvent<String>> conflict = collect(input("wire-scoped-id", 201L, "changed fictional question"));
        assertError(conflict, "409");
        assertEquals(1, modelStreams.size());
        user(102L);
        assertError(collect(owner), "404");
        ConsultationStreamDTO other = input("wire-scoped-id", 202L, "other fictional question");
        List<ServerSentEvent<String>> otherEvents = new CopyOnWriteArrayList<>();
        controller.streamChat(other).subscribe(otherEvents::add);
        assertEquals(2, modelStreams.size());
        emit(0, "owner private fictional reply");
        emit(1, "other fictional reply");
        complete(0, 101L);
        complete(1, 102L);
        assertEquals("other fictional reply", content(otherEvents));
        assertEquals(2, db.count("ai_request_record"));
        assertEquals(4, db.count("consultation_message"));
        assertNotEquals(db.requests.find(101L, owner.getClientRequestId()).getRequestId(), db.requests.find(102L, other.getClientRequestId()).getRequestId());
    }

    @Test
    void typedTimeoutCommitsOnlyOnePartialAndNeverReinvokesAnUnknownRequest() {
        ConsultationStreamDTO input = input("wire-timeout-id", 201L, "timeout fictional question");
        List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        controller.streamChat(input).subscribe(events::add);
        emit(0, "已保存的超时残句");
        failModel(0, 101L, new HttpTimeoutException("fictional upstream deadline"));
        assertError(events, "504");
        JsonNode error = data(events.get(events.size() - 1));
        assertTrue(error.at("/data/saved").asBoolean());
        assertEquals("UNKNOWN", error.at("/data/status").asText());
        assertEquals(2, db.count("consultation_message"));
        List<ServerSentEvent<String>> retry = collect(input);
        assertError(retry, "409");
        assertEquals(error.at("/data/requestId"), data(retry.get(0)).at("/data/requestId"));
        assertEquals(1, modelStreams.size());
        var history = controller.getSessionDetail(201L).getData();
        assertEquals("UNKNOWN", history.get(1).getRequestStatus());
        assertEquals("504", history.get(1).getRequestErrorCode());
        assertTrue(history.get(1).getSaved());
    }

    @Test
    void aiWriteFailureNeverEmitsDoneAndARepeatedPostCannotRestartTheModel() {
        ConsultationStreamDTO input = input("wire-save-failure", 201L, "save failure fictional question");
        List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        controller.streamChat(input).subscribe(events::add);
        db.jdbc.execute("CREATE TRIGGER batch3_fail_message BEFORE INSERT ON consultation_message FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected fixture failure'");
        emit(0, "显示但不能保存的片段");
        complete(0, 101L);
        assertError(events, "500");
        assertFalse(data(events.get(events.size() - 1)).at("/data/saved").asBoolean());
        assertEquals("显示但不能保存的片段", content(events));
        assertEquals(1, db.count("consultation_message"));
        db.jdbc.execute("DROP TRIGGER batch3_fail_message");
        assertError(collect(input), "409");
        assertEquals(1, modelStreams.size());
        assertEquals("UNKNOWN", db.requests.find(101L, input.getClientRequestId()).getStatus());
        controller.streamChat(input("wire-explicit-new", 201L, "explicit new question")).subscribe();
        assertEquals(2, modelStreams.size());
    }

    @Test
    void initializationFailureIsDurablyFailedAndSameIdReturnsItsOriginalError() {
        doThrow(new IllegalStateException("injected memory failure")).when(memory).get("conversation_session_201");
        ConsultationStreamDTO input = input("wire-init-failure", 201L, "initialization fictional question");
        List<ServerSentEvent<String>> first = collect(input);
        assertError(first, "500");
        assertEquals("FAILED", db.requests.find(101L, input.getClientRequestId()).getStatus());
        List<ServerSentEvent<String>> repeated = collect(input);
        assertError(repeated, "500");
        assertEquals(data(first.get(0)).at("/data/requestId"), data(repeated.get(0)).at("/data/requestId"));
        assertEquals(0, modelStreams.size());
        assertEquals(1, db.count("consultation_message"));
        assertNull(registry.findActive(101L));
    }

    @Test
    void deletedSessionHidesBothRequestStatusAndSuccessfulReplay() {
        ConsultationStreamDTO input = input("wire-delete-history", 201L, "deleted session fictional question");
        controller.streamChat(input).subscribe();
        emit(0, "fictional saved reply");
        complete(0, 101L);
        var previouslyRead = db.requests.find(101L, input.getClientRequestId());
        assertEquals("200", controller.getRequestStatus(input.getClientRequestId()).getCode());
        assertEquals("200", controller.deleteSession(201L).getCode());
        assertEquals("404", controller.getRequestStatus(input.getClientRequestId()).getCode());
        assertError(collect(input), "404");
        assertThrows(com.flj.AiService.ReplyFailure.class, () -> db.requests.replay(previouslyRead),
                "Deletion after an earlier request lookup must still prevent reading the saved reply");
        assertEquals(1, modelStreams.size());
        assertEquals(2, db.count("consultation_message"));
    }

    @Test
    void ledgerDatetimeRoundTripsShanghaiWallTimeEvenWithADifferentJvmTimezone() {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
        var request = db.create("wire-timezone-id");
        LocalDateTime after = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(1);
        assertFalse(request.getCreatedAt().isBefore(before));
        assertFalse(request.getCreatedAt().isAfter(after));
        LocalDateTime stored = db.jdbc.queryForObject("SELECT created_at FROM ai_request_record WHERE request_id=?",
                (row, index) -> row.getObject(1, LocalDateTime.class), request.getRequestId());
        assertEquals(stored, request.getCreatedAt());
    }

    private void user(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("token", JwtTokenUtil.generateToken(userId, "fictional-" + userId, 1));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ConsultationStreamDTO input(String clientId, Long session, String question) {
        ConsultationStreamDTO dto = new ConsultationStreamDTO();
        dto.setClientRequestId(clientId);
        dto.setSessionId("session_" + session);
        dto.setUserMessage(question);
        return dto;
    }

    private List<ServerSentEvent<String>> collect(ConsultationStreamDTO input) {
        return controller.streamChat(input).collectList().block(Duration.ofSeconds(5));
    }

    private void emit(int stream, String text) {
        assertEquals(Sinks.EmitResult.OK, modelStreams.get(stream).tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))));
    }

    private void complete(int stream, Long userId) {
        var handle = registry.findActive(userId);
        assertNotNull(handle);
        assertEquals(Sinks.EmitResult.OK, modelStreams.get(stream).tryEmitComplete());
        assertTrue(handle.awaitTermination(5000), "Generation must finish its database transaction and observer delivery");
    }

    private void failModel(int stream, Long userId, Throwable failure) {
        var handle = registry.findActive(userId);
        assertNotNull(handle);
        assertEquals(Sinks.EmitResult.OK, modelStreams.get(stream).tryEmitError(failure));
        assertTrue(handle.awaitTermination(5000), "Generation must finish its failure transaction and observer delivery");
    }

    private static JsonNode data(ServerSentEvent<String> event) {
        try { return JSON.readTree(event.data()); }
        catch (Exception error) { throw new AssertionError("Invalid SSE JSON", error); }
    }

    private static String content(List<ServerSentEvent<String>> events) {
        return events.stream().filter(event -> "message".equals(event.event()))
                .map(event -> data(event).at("/data/content").asText()).reduce("", String::concat);
    }

    private static void assertError(List<ServerSentEvent<String>> events, String code) {
        assertFalse(events.isEmpty());
        assertTrue(events.stream().noneMatch(event -> "done".equals(event.event())));
        ServerSentEvent<String> terminal = events.get(events.size() - 1);
        assertEquals("error", terminal.event());
        assertEquals(code, data(terminal).path("code").asText());
    }
}
