package com.flj.AiService;

import com.flj.DTO.command.ConsultationSessionCreateDTO;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallFailures;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.AiRequestRecord;
import com.flj.service.AiRequestService;
import com.flj.service.ConsultationMessageService;
import com.flj.service.ConsultationSessionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PsychologicalSupportService {
    private static final Logger log = LoggerFactory.getLogger(PsychologicalSupportService.class);

    @Value("${app.ai.reply-timeout-ms:120000}")
    private long replyTimeoutMillis = 120000;

    private final Set<String> invalidatedConversations = ConcurrentHashMap.newKeySet();

    @Autowired
    @Qualifier("open-ai")
    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ActiveReplyRegistry activeReplyRegistry;

    @Autowired
    private AiCallGovernor aiCallGovernor;

    @Autowired
    private AiRequestService aiRequestService;

    @Autowired
    private OpenAiChatModel openAiChatModel;

    public String requestModel() {
        return openAiChatModel == null ? "openai" : openAiChatModel.getDefaultOptions().getModel();
    }

    /** Length-prefixed canonical inputs keep a changed body/config from reusing a key. */
    public String requestFingerprint(Long sessionId, String userMessage) {
        try {
            ObjectMapper json = new ObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            String options = openAiChatModel == null ? "{}" : json.writeValueAsString(openAiChatModel.getDefaultOptions());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("consultation-v1", sessionId.toString(), userMessage,
                    PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT, options, String.valueOf(aiCallGovernor.getMaxOutputTokens()))) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot fingerprint generation options", error);
        }
    }

    // 对话记忆窗口大小，与 ChatClientConfig 中 maxMessages 保持一致
    private static final int MEMORY_WINDOW_SIZE = 30;

    public StructOutPut.StreamChatSession startSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 创建数据库会话记录（初始消息由 stream 接口统一保存，避免重复写入）
        ConsultationSession consultationSession = consultationSessionService.createSession(userId, createDTO);

        // 创建会话信息
        String sessionId = "session_" + consultationSession.getId();
        return new StructOutPut.StreamChatSession(
                sessionId,
                userId,
                createDTO.getInitialMessage(),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86400000L, // 24小时
                0,
                "ACTIVE"
        );
    }

    /** Generation owns cleanup: persist, release the slot, then end every observer. */
    public Flux<String> streamPsychologicalChat(ConsultationSession session, String userMessage,
                                                Long userId, ActiveReplyHandle handle) {
        new ReplyTask(session.getId(), userId, handle).start(userMessage);
        return handle.asFlux();
    }

    private enum End { COMPLETE, STOP, DELETE, TIMEOUT, MODEL_ERROR, INITIALIZATION_ERROR, REJECTED, OUTPUT_LIMIT }

    private final class ReplyTask {
        private final Long sessionId;
        private final Long userId;
        private final ActiveReplyHandle handle;
        private final String conversationId;
        private final StringBuilder fullResponse = new StringBuilder();
        private AiCallGovernor.Lease lease;
        private long responseBytes;
        private int responseFragments;
        private long maxReplyBytes;
        private int maxReplyFragments;
        private boolean initializing = true;
        private boolean terminal;
        private boolean dispatched;
        private volatile boolean deadlineReached;

        private ReplyTask(Long sessionId, Long userId, ActiveReplyHandle handle) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.handle = handle;
            this.conversationId = "conversation_session_" + sessionId;
        }

        private void start(String userMessage) {
            handle.onStop(this::stop);
            try {
                if (!handle.isStopRequested()) {
                    lease = aiCallGovernor.acquire(userId, "consultation");
                    maxReplyBytes = aiCallGovernor.getMaxReplyBytes();
                    maxReplyFragments = aiCallGovernor.getMaxReplyFragments();
                    boolean invalidated = invalidatedConversations.contains(conversationId);
                    List<Message> history = invalidated ? List.of() : chatMemory.get(conversationId);
                    if (invalidated || history.isEmpty()) {
                        chatMemory.clear(conversationId);
                        history = rebuildConversationMemory(conversationId, sessionId, handle.getPersistentRequest());
                        invalidatedConversations.remove(conversationId);
                    }
                    List<String> input = new ArrayList<>();
                    input.add(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT);
                    history.stream().map(Message::getText).forEach(input::add);
                    input.add(userMessage);
                    lease.validateInput(input.toArray(String[]::new));
                    if (!handle.isStopRequested() && handle.getPersistentRequest() == null) {
                        consultationMessageService.saveUserMessage(sessionId, userMessage, null);
                    }
                }
                synchronized (this) { initializing = false; }
                if (handle.isStopRequested()) {
                    stop();
                    return;
                }
                boolean started = handle.startIfAllowed(() -> {
                    if (handle.getPersistentRequest() != null) { aiRequestService.markDispatched(handle.getPersistentRequest()); }
                    lease.markStarted();
                    dispatched = true;
                    return chatClient.prompt()
                        .options(OpenAiChatOptions.builder().maxTokens(aiCallGovernor.getMaxOutputTokens()).build())
                        .user(userMessage)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream().content()
                        .takeUntilOther(Mono.delay(Duration.ofMillis(replyTimeoutMillis))
                                .doOnNext(ignored -> deadlineReached = true))
                        .subscribe(this::next, error -> finish(AiCallFailures.isTimeout(error) ? End.TIMEOUT : End.MODEL_ERROR, error),
                                () -> finish(deadlineReached ? End.TIMEOUT : End.COMPLETE, null));
                });
                if (!started) { stop(); }
            } catch (Exception error) {
                End end = error instanceof AiCallRejectedException ? End.REJECTED
                        : dispatched ? (AiCallFailures.isTimeout(error) ? End.TIMEOUT : End.MODEL_ERROR)
                        : End.INITIALIZATION_ERROR;
                finish(end, error);
            }
        }

        private void next(String fragment) {
            boolean overLimit;
            synchronized (this) {
                if (terminal) { return; }
                long fragmentBytes = AiCallGovernor.utf8Bytes(fragment);
                overLimit = fragmentBytes > maxReplyBytes - responseBytes || responseFragments >= maxReplyFragments;
                if (!overLimit) {
                    responseBytes += fragmentBytes;
                    responseFragments++;
                    fullResponse.append(fragment);
                }
            }
            if (overLimit) {
                finish(End.OUTPUT_LIMIT, null);
                return;
            }
            handle.emitNext(fragment);
        }

        private void stop() {
            synchronized (this) {
                // Keep the slot until an in-progress database initialization operation returns.
                if (initializing) { return; }
            }
            finish(handle.isDiscard() ? End.DELETE : End.STOP, null);
        }

        private void finish(End end, Throwable cause) {
            String response;
            synchronized (this) {
                if (terminal) { return; }
                terminal = true;
                initializing = false;
                response = fullResponse.toString();
            }

            ReplyFailure failure = null;
            boolean saved = false;
            try {
                handle.terminateGeneration();
                if (handle.getPersistentRequest() == null && (end == End.COMPLETE || end == End.STOP || end == End.TIMEOUT) && !response.isEmpty()) {
                    consultationMessageService.saveAimessage(sessionId, response, "openai");
                    saved = true;
                }
                if (end == End.TIMEOUT) {
                    failure = new ReplyFailure("504", "回复生成超时", handle.getRequestId(), saved);
                } else if (end == End.MODEL_ERROR) {
                    failure = new ReplyFailure("502", "回复生成失败，请稍后重试", handle.getRequestId(), false, cause);
                } else if (end == End.INITIALIZATION_ERROR) {
                    failure = new ReplyFailure("500", "回复初始化失败，请稍后重试", handle.getRequestId(), false, cause);
                } else if (end == End.REJECTED && cause instanceof AiCallRejectedException rejected) {
                    failure = new ReplyFailure(rejected.getCode(), rejected.getMessage(), handle.getRequestId(), false, rejected);
                } else if (end == End.OUTPUT_LIMIT) {
                    failure = new ReplyFailure("429", "回复达到输出限制，请缩小问题范围", handle.getRequestId(), false);
                }
                if (handle.getPersistentRequest() != null) {
                    String status = (end == End.COMPLETE || end == End.STOP) ? "SUCCEEDED"
                            : dispatched && (end == End.TIMEOUT || end == End.MODEL_ERROR) ? "UNKNOWN" : "FAILED";
                    if (end == End.DELETE) { status = "FAILED"; }
                    AiRequestRecord persisted = aiRequestService.finish(handle.getPersistentRequest(), status,
                            end == End.DELETE ? "410" : failure == null ? null : failure.getCode(),
                            end == End.DELETE ? "会话已删除，回复未保存" : failure == null ? null : failure.getMessage(), response,
                            end == End.COMPLETE || end == End.STOP || end == End.TIMEOUT);
                    handle.setPersistentRequest(persisted);
                    saved = persisted.isSaved();
                    if (failure != null) {
                        failure = new ReplyFailure(failure.getCode(), failure.getMessage(), handle.getRequestId(), saved, cause);
                    } else if (!"SUCCEEDED".equals(persisted.getStatus())) {
                        failure = new ReplyFailure(persisted.getErrorCode(), persisted.getErrorMessage(), handle.getRequestId(), saved);
                    }
                }
            } catch (Exception error) {
                failure = new ReplyFailure("500", "回复未能保存，请保留当前内容", handle.getRequestId(), false, error);
                if (handle.getPersistentRequest() != null) {
                    try {
                        handle.setPersistentRequest(aiRequestService.finish(handle.getPersistentRequest(), "UNKNOWN", "500",
                                "回复保存状态待确认，请保留当前内容", null, false));
                        failure = new ReplyFailure("500", "回复保存状态待确认，请保留当前内容", handle.getRequestId(), handle.getPersistentRequest().isSaved(), error);
                    } catch (Exception ignored) {
                        // A durable RUNNING record is reconciled on restart or the next explicit status query.
                        handle.getPersistentRequest().setStatus("UNKNOWN");
                        failure = new ReplyFailure("500", "回复保存状态待确认，请保留当前内容", handle.getRequestId(), (Boolean) null, error);
                    }
                }
            } finally {
                if (end != End.COMPLETE || failure != null) {
                    invalidatedConversations.add(conversationId);
                    try {
                        chatMemory.clear(conversationId);
                    } catch (Exception error) {
                        log.warn("回复记忆清理失败, requestId={}, errorType={}", handle.getRequestId(), error.getClass().getSimpleName());
                    }
                }
                try {
                    if (lease != null) { lease.close(); }
                } finally {
                    activeReplyRegistry.release(userId, handle);
                }
            }
            if (failure == null) {
                handle.emitComplete();
            } else {
                log.warn("回复终止, requestId={}, code={}, saved={}", failure.getRequestId(), failure.getCode(), failure.isSaved());
                handle.emitError(failure);
            }
        }
    }

    /**
     * 从消息库回放最近消息，重建对话记忆（受窗口大小约束）
     */
    private List<Message> rebuildConversationMemory(String conversationId, Long sessionId, AiRequestRecord currentRequest) {
        List<ConsultationMessage> recentMessages = new ArrayList<>(
                consultationMessageService.getRecentMessages(sessionId, MEMORY_WINDOW_SIZE));
        if (recentMessages.isEmpty()) {
            return List.of();
        }
        // 取出时为 id 倒序，反转为时间正序后回放
        Collections.reverse(recentMessages);
        List<Message> messages = recentMessages.stream()
                .filter(message -> currentRequest == null || !message.getId().equals(currentRequest.getUserMessageId()))
                .map(message -> message.getSenderType() != null && message.getSenderType() == 2
                        ? (Message) new AssistantMessage(message.getContent())
                        : (Message) new UserMessage(message.getContent()))
                .toList();

        chatMemory.add(conversationId, messages);
        return messages;
    }

    // 获取参数中的sessionId
    public Long extractSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("session_")) {
            String idStr = sessionId.substring("session_".length());
            try {
                return Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
