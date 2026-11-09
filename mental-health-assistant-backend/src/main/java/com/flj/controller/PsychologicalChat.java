package com.flj.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import com.flj.AiService.ActiveReplyHandle;
import com.flj.AiService.ActiveReplyRegistry;
import com.flj.AiService.EmotionAnalysisService;
import com.flj.AiService.PsychologicalSupportService;
import com.flj.AiService.ReplyFailure;
import com.flj.AiService.StructOutPut;
import com.flj.DTO.command.ConsultationSessionCreateDTO;
import com.flj.DTO.command.ConsultationStreamDTO;
import com.flj.DTO.response.ActiveReplyStatusDTO;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.ConsultationSessionResponseDTO;
import com.flj.DTO.response.SessionEmotionResponseDTO;
import com.flj.common.Result;
import com.flj.common.ResultCode;
import com.flj.entity.ConsultationSession;
import com.flj.entity.AiRequestRecord;
import com.flj.service.AiRequestService;
import com.flj.service.ConsultationMessageService;
import com.flj.service.ConsultationSessionService;
import com.flj.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;

@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    private static final Logger log = LoggerFactory.getLogger(PsychologicalChat.class);
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    @Value("${app.ai.stop-wait-ms:3000}")
    private long stopWaitMillis = 3000;
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    @Autowired
    private ActiveReplyRegistry activeReplyRegistry;

    @Autowired
    private AiRequestService aiRequestService;

    @PostMapping("/session/start")
    public Result<StructOutPut.StreamChatSession> startSession(@Valid @RequestBody ConsultationSessionCreateDTO createDTO) {
        // 获取当前用户
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        // 单活跃流守卫（ADR 0009）：新会话的第一条消息也是消息，
        // 此处前置拒绝可避免"空会话已建、首条流被 409"的孤儿会话
        if (activeReplyRegistry.findActive(userId) != null) {
            return Result.error("409", "您有一条AI回复正在生成中，请等待完成或点击停止", null);
        }
        StructOutPut.StreamChatSession session = psychologicalSupportService.startSession(userId, createDTO);
        return Result.ok(session);
    }

    /**
     * 用户端会话分页列表（仅本人、未删除）
     */
    @GetMapping("/sessions")
    public Result<Page<ConsultationSessionResponseDTO>> getSessionList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(consultationSessionService.pageUserSessions(userId, pageNum, pageSize));
    }

    /**
     * 用户端软删除会话（归属不符返回 404 防枚举）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        if (consultationSessionService.findOwnedSession(userId, sessionId) == null) {
            return Result.error("404", "会话不存在", null);
        }
        // Mark deleted before stopping so a later subscription cannot start a new reply.
        boolean deleted = consultationSessionService.softDeleteSession(userId, sessionId);
        if (!deleted) {
            return Result.error("404", "会话不存在", null);
        }
        activeReplyRegistry.stopIfSession(userId, sessionId);
        return Result.ok();
    }

    /**
     * 用户端会话消息（按 id 升序）
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessageResponseDTO>> getSessionDetail(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        ConsultationSession session = consultationSessionService.findOwnedSession(userId, sessionId);
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return Result.error("404", "会话不存在", null);
        }
        return Result.ok(consultationMessageService.listMessagesBySessionId(sessionId));
    }

    /**
     * 会话情绪分析（懒计算+缓存：新鲜则返回库中快照，过期则同步分析；失败降级旧快照/中性值）
     */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<SessionEmotionResponseDTO> getSessionEmotion(@PathVariable String sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        Long dbSessionId = psychologicalSupportService.extractSessionId(sessionId);
        ConsultationSession session = dbSessionId != null
                ? consultationSessionService.findOwnedSession(userId, dbSessionId)
                : null;
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return Result.error("404", "会话不存在", null);
        }
        return Result.ok(emotionAnalysisService.getOrAnalyzeEmotion(session.getId()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ConsultationStreamDTO streamDTO) {
        // 获取当前用户

        Long userId = getCurrentUserId();
        if (userId == null) {
            return errorEvent(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }

        // 校验会话归属与状态（修复越权写入）
        Long dbSessionId = psychologicalSupportService.extractSessionId(streamDTO.getSessionId());
        ConsultationSession session = dbSessionId != null
                ? consultationSessionService.findOwnedSession(userId, dbSessionId)
                : null;
        if (session == null) {
            return errorEvent("404", "会话不存在");
        }
        if (Boolean.TRUE.equals(session.getDeleted())) {
            return errorEvent("404", "会话已删除");
        }

        // 单活跃流守卫（ADR 0009）：Flux.defer 使槽位抢占发生在订阅时刻，与流启动原子衔接——
        // 订阅不发生则不占槽，无泄漏窗口；并发抢占由 tryClaim 的 putIfAbsent 原子性保证
        // 只有在被订阅那一刻才执行
        return Flux.defer(() -> {
            String fingerprint;
            try {
                fingerprint = psychologicalSupportService.requestFingerprint(session.getId(), streamDTO.getUserMessage());
                AiRequestRecord existing = aiRequestService.find(userId, streamDTO.getClientRequestId());
                if (existing != null) { return replayRequest(existing, session.getId(), fingerprint, userId); }
            } catch (Exception error) {
                return errorEvent("503", "请求状态暂时不可用，请勿自动重新生成", UUID.randomUUID().toString(), null, "UNKNOWN");
            }
            ActiveReplyHandle handle = activeReplyRegistry.tryClaim(userId, session.getId(), streamDTO.getClientRequestId(), fingerprint);
            if (handle == null) {
                // A same-key duplicate may arrive between claim and the transaction commit.
                ActiveReplyHandle active = activeReplyRegistry.findActive(userId);
                if (active != null && Objects.equals(streamDTO.getClientRequestId(), active.getClientRequestId())) {
                    if (!session.getId().equals(active.getSessionId()) || !Objects.equals(fingerprint, active.getRequestFingerprint())) {
                        return errorEvent("409", "同一请求ID的参数已改变，请使用新的请求ID", active.getRequestId(), false, "CONFLICT");
                    }
                    return toSse(active.asFlux(), active.getRequestId(), active);
                }
                AiRequestRecord duplicate = aiRequestService.find(userId, streamDTO.getClientRequestId());
                if (duplicate != null) { return replayRequest(duplicate, session.getId(), fingerprint, userId); }
                return errorEvent("409", "您有一条AI回复正在生成中，请等待完成或点击停止");
            }
            try {
                    ConsultationSession current = consultationSessionService.findOwnedSession(userId, session.getId());
                    if (current == null || Boolean.TRUE.equals(current.getDeleted())) {
                        activeReplyRegistry.release(userId, handle);
                        handle.failBeforeStart(new ReplyFailure("404", "会话不存在", handle.getRequestId(), false));
                        return toSse(handle.asFlux(), handle.getRequestId(), handle);
                    }
                    try {
                        handle.setPersistentRequest(aiRequestService.create(handle.getRequestId(), userId,
                                streamDTO.getClientRequestId(), current.getId(), fingerprint,
                                psychologicalSupportService.requestModel(), streamDTO.getUserMessage()));
                    } catch (DuplicateKeyException duplicate) {
                        activeReplyRegistry.release(userId, handle);
                        AiRequestRecord original = aiRequestService.find(userId, streamDTO.getClientRequestId());
                        handle.failBeforeStart(new ReplyFailure("409", "请求已存在，请查询原请求", handle.getRequestId(), false));
                        if (original == null) { return errorEvent("409", "请求结果待确认，请勿自动重新生成", handle.getRequestId(), false, "UNKNOWN"); }
                        return replayRequest(original, current.getId(), fingerprint, userId);
                    }
                    return toSse(psychologicalSupportService.streamPsychologicalChat(current, streamDTO.getUserMessage(), userId, handle), handle.getRequestId(), handle);
                } catch (Exception error) {
                    if (handle.getPersistentRequest() != null) {
                        try { handle.setPersistentRequest(aiRequestService.finish(handle.getPersistentRequest(), "FAILED", "500", "回复初始化失败，请稍后重试", null, false)); }
                        catch (Exception ignored) { handle.getPersistentRequest().setStatus("UNKNOWN"); }
                    }
                    activeReplyRegistry.release(userId, handle);
                    handle.failBeforeStart(error instanceof ReplyFailure failure ? failure
                            : new ReplyFailure("500", "回复初始化失败，请稍后重试", handle.getRequestId(), false, error));
                    return toSse(handle.asFlux(), handle.getRequestId(), handle);
                }
        }).onErrorResume(error -> errorEvent("503", "请求状态暂时不可用，请勿自动重新生成", UUID.randomUUID().toString(), null, "UNKNOWN"));
    }

    /**
     * 重连观看（ADR 0010）：页面刷新后重进、或本标签页切回生成中会话时，重新订阅活跃回复的
     * 观察流。replay 语义：迟到订阅者收全量回放后接实时片段；完成瞬间的迟到者直接收
     * "全量回放+done"。404（无活跃回复/不属于该会话）是正常扑空信号，前端回退拉取消息列表。
     * 注册表按 userId 键控：句柄即本人活跃回复，claim 时已校验会话归属，sessionId 匹配即安全。
     */
    @PostMapping(value = "/stream/attach/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> attachStream(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return errorEvent(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }
        ConsultationSession session = consultationSessionService.findOwnedSession(userId, sessionId);
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return errorEvent("404", "会话不存在");
        }
        ActiveReplyHandle handle = activeReplyRegistry.findActive(userId);
        if (handle == null || !sessionId.equals(handle.getSessionId())) {
            return errorEvent("404", "没有正在生成的回复");
        }
        return toSse(handle.asFlux(), handle.getRequestId(), handle);
    }

    /**
     * 显式停止当前活跃回复（ADR 0009）：停止是命令而非断开连接。
     * 残句按契约落库后才返回，前端收到响应即可刷新视图。
     */
    @PostMapping("/stream/stop")
    public Result<Map<String, Object>> stopStream() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        ActiveReplyHandle stopped = activeReplyRegistry.stopCurrent(userId, false);
        if (stopped == null) {
            return Result.error("404", "没有正在生成的回复", null);
        }
        if (!stopped.awaitTermination(stopWaitMillis)) {
            return Result.error("409", "停止请求已提交，结果待确认，请等待当前回复结束",
                    Map.of("requestId", stopped.getRequestId(), "stopping", true));
        }
        ReplyFailure failure = stopped.getFailure();
        if (failure != null) {
            String status = stopped.getPersistentRequest() == null ? "FAILED" : stopped.getPersistentRequest().getStatus();
            Map<String, Object> data = new HashMap<>();
            data.put("requestId", failure.getRequestId()); data.put("saved", failure.getSaved()); data.put("status", status);
            return Result.error(failure.getCode(), failure.getMessage(),
                    data);
        }
        return Result.ok();
    }

    /**
     * 活跃回复状态查询（ADR 0009）：前端刷新后恢复输入锁定态、会话列表"生成中"徽标。
     */
    @GetMapping("/stream/active")
    public Result<ActiveReplyStatusDTO> getActiveReply() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        ActiveReplyHandle handle = activeReplyRegistry.findActive(userId);
        if (handle == null) {
            return Result.ok(null);
        }
        return Result.ok(new ActiveReplyStatusDTO(handle.getSessionId(), handle.getStartedAt(), handle.getRequestId(), handle.getClientRequestId()));
    }

    @GetMapping("/stream/requests/{clientRequestId}")
    public Result<Map<String, Object>> getRequestStatus(@PathVariable String clientRequestId) {
        Long userId = getCurrentUserId();
        if (userId == null) { return Result.error("401", "用户未登录", null); }
        AiRequestRecord request = aiRequestService.find(userId, clientRequestId);
        ConsultationSession session = request == null ? null : consultationSessionService.findOwnedSession(userId, request.getSessionId());
        if (request == null || session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return Result.error("404", "请求不存在或已过期", null);
        }
        if ("RUNNING".equals(request.getStatus())) {
            ActiveReplyHandle active = activeReplyRegistry.findActive(userId);
            if (active == null || !request.getRequestId().equals(active.getRequestId())) { request = aiRequestService.markUnknown(request); }
        }
        Map<String, Object> data = requestData(request);
        return Result.ok(data);
    }

    private Flux<ServerSentEvent<String>> replayRequest(AiRequestRecord request, Long sessionId, String fingerprint, Long userId) {
        if (!sessionId.equals(request.getSessionId()) || !Objects.equals(fingerprint, request.getRequestFingerprint())) {
            return errorEvent("409", "同一请求ID的参数已改变，请使用新的请求ID", request.getRequestId(), request.isSaved(), "CONFLICT");
        }
        ActiveReplyHandle active = activeReplyRegistry.findActive(userId);
        if ("RUNNING".equals(request.getStatus()) && active != null && request.getRequestId().equals(active.getRequestId())) {
            return toSse(active.asFlux(), active.getRequestId(), active);
        }
        if ("RUNNING".equals(request.getStatus())) { request = aiRequestService.markUnknown(request); }
        if (!"SUCCEEDED".equals(request.getStatus())) {
            boolean unknown = "UNKNOWN".equals(request.getStatus());
            return errorEvent(unknown || request.getErrorCode() == null ? "409" : request.getErrorCode(),
                    request.getErrorMessage() == null ? "请求结果待确认，请勿自动重新生成" : request.getErrorMessage(),
                    request.getRequestId(), request.isSaved(), request.getStatus(), unknown ? request.getErrorCode() : null);
        }
        AiRequestRecord terminal = request;
        return Flux.defer(() -> {
            String response = aiRequestService.replay(terminal);
            Flux<ServerSentEvent<String>> content = response.isEmpty() ? Flux.empty() : Flux.just(messageEvent(response, terminal.getRequestId()));
            return content.concatWith(Flux.just(ServerSentEvent.<String>builder().event("done")
                    .data(JSONUtil.toJsonStr(requestData(terminal))).build()));
        }).onErrorResume(error -> errorEvent("409", "原回复结果待确认，请勿自动重新生成", terminal.getRequestId(), terminal.isSaved(), "UNKNOWN"));
    }

    private Map<String, Object> requestData(AiRequestRecord request) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", request.getRequestId()); data.put("clientRequestId", request.getClientRequestId());
        data.put("sessionId", request.getSessionId()); data.put("status", request.getStatus()); data.put("saved", request.isSaved());
        if (request.getErrorCode() != null) { data.put("code", request.getErrorCode()); }
        if (request.getErrorMessage() != null) { data.put("msg", request.getErrorMessage()); }
        return data;
    }

    // 从当前请求的 JWT 中解析用户ID
    private Long getCurrentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        if (token == null) {
            return null;
        }
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    // 构造 SSE 错误事件
    private Flux<ServerSentEvent<String>> errorEvent(String code, String msg) {
        return errorEvent(code, msg, UUID.randomUUID().toString(), false);
    }

    private Flux<ServerSentEvent<String>> errorEvent(String code, String msg, String requestId, boolean saved) {
        return errorEvent(code, msg, requestId, saved, "FAILED");
    }

    private Flux<ServerSentEvent<String>> errorEvent(String code, String msg, String requestId, Boolean saved, String status) {
        return errorEvent(code, msg, requestId, saved, status, null);
    }

    private Flux<ServerSentEvent<String>> errorEvent(String code, String msg, String requestId, Boolean saved, String status, String originalErrorCode) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", requestId); data.put("saved", saved); data.put("status", status);
        if (originalErrorCode != null) { data.put("originalErrorCode", originalErrorCode); }
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(JSONUtil.toJsonStr(Result.error(code, msg, data)))
                .build());
    }

    // 观察流 → SSE 事件（/stream 与 /stream/attach 共用同一事件结构，ADR 0010）
    private ServerSentEvent<String> messageEvent(String fragment, String requestId) {
        return ServerSentEvent.<String>builder().event("message")
                .data(JSONUtil.toJsonStr(Result.ok(Map.of("content", fragment, "type", "normal", "requestId", requestId)))).build();
    }

    private Flux<ServerSentEvent<String>> toSse(Flux<String> fragments, String requestId, ActiveReplyHandle handle) {
        Flux<ServerSentEvent<String>> replies = fragments
                .map(fragment -> messageEvent(fragment, requestId))
                .concatWith(Flux.defer(() -> Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data(JSONUtil.toJsonStr(handle.getPersistentRequest() == null ? Map.of("requestId", requestId, "status", "SUCCEEDED") : requestData(handle.getPersistentRequest())))
                        .build())))
                .onErrorResume(error -> {
                    if (error instanceof ReplyFailure failure) {
                        log.warn("Reply failed, requestId={}, code={}, saved={}", failure.getRequestId(), failure.getCode(), failure.isSaved());
                        String status = handle.getPersistentRequest() == null ? "FAILED" : handle.getPersistentRequest().getStatus();
                        return errorEvent(failure.getCode(), failure.getMessage(), failure.getRequestId(), failure.getSaved(), status);
                    }
                    log.error("Unexpected reply failure, requestId={}, type={}", requestId, error.getClass().getSimpleName());
                    return errorEvent("500", "回复处理失败，请稍后重试", requestId, false);
                });
        // Comments keep this observer's connection alive without changing the reply payload or task lifecycle.
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(heartbeatInterval).onBackpressureDrop()
                .map(tick -> ServerSentEvent.<String>builder().comment("heartbeat").build());
        return Flux.merge(replies, heartbeats)
                .takeUntil(event -> "done".equals(event.event()) || "error".equals(event.event()));
    }
}
