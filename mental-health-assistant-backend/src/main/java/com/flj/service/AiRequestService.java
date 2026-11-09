package com.flj.service;

import com.flj.AiService.ReplyFailure;
import com.flj.entity.AiRequestRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** Single-instance request ledger; model calls never run inside these transactions. */
@Service
@EnableScheduling
public class AiRequestService {
    public static final String SCENE = "consultation";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final RowMapper<AiRequestRecord> ROW = (rs, rowNum) -> {
        AiRequestRecord r = new AiRequestRecord();
        r.setRequestId(rs.getString("request_id")); r.setUserId(rs.getLong("user_id"));
        r.setScene(rs.getString("scene")); r.setClientRequestId(rs.getString("client_request_id"));
        r.setRequestFingerprint(rs.getString("request_fingerprint")); r.setSessionId(rs.getObject("session_id", Long.class));
        r.setStatus(rs.getString("status")); r.setUserMessageId(rs.getObject("user_message_id", Long.class));
        r.setAiMessageId(rs.getObject("ai_message_id", Long.class)); r.setAiModel(rs.getString("ai_model"));
        r.setDispatched(rs.getBoolean("dispatched")); r.setErrorCode(rs.getString("error_code"));
        r.setErrorMessage(rs.getString("error_message")); r.setSaved(rs.getBoolean("saved"));
        r.setCreatedAt(rs.getObject("created_at", LocalDateTime.class)); r.setStartedAt(rs.getObject("started_at", LocalDateTime.class));
        r.setFinishedAt(rs.getObject("finished_at", LocalDateTime.class)); r.setExpiresAt(rs.getObject("expires_at", LocalDateTime.class));
        return r;
    };

    public AiRequestService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }

    public AiRequestRecord find(Long userId, String clientRequestId) {
        List<AiRequestRecord> found = jdbc.query("SELECT * FROM ai_request_record WHERE user_id=? AND scene=? AND client_request_id=?",
                ROW, userId, SCENE, clientRequestId);
        if (found.isEmpty()) { return null; }
        AiRequestRecord r = found.get(0);
        if (!"RUNNING".equals(r.getStatus()) && r.getExpiresAt() != null && !r.getExpiresAt().isAfter(now())) {
            jdbc.update("DELETE FROM ai_request_record WHERE request_id=? AND status<>'RUNNING' AND expires_at<=?", r.getRequestId(), now());
            return null;
        }
        return r;
    }

    public Map<String, AiRequestRecord> findByRequestIds(Collection<String> requestIds) {
        if (requestIds.isEmpty()) { return Map.of(); }
        String placeholders = requestIds.stream().map(id -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT * FROM ai_request_record WHERE scene='consultation' AND request_id IN (" + placeholders + ")", ROW, requestIds.toArray())
                .stream().collect(Collectors.toMap(AiRequestRecord::getRequestId, r -> r));
    }

    /** The unique key and user-message link commit together or both roll back. */
    public AiRequestRecord create(String requestId, Long userId, String clientRequestId, Long sessionId,
                                  String fingerprint, String model, String userMessage) {
        return transactions.execute(tx -> {
            if (!lockOwnedSession(userId, sessionId)) {
                throw new ReplyFailure("404", "会话不存在", requestId, false);
            }
            LocalDateTime created = now();
            jdbc.update("INSERT INTO ai_request_record (request_id,user_id,scene,client_request_id,request_fingerprint,session_id,status,ai_model,dispatched,saved,created_at) VALUES (?,?,?,?,?,?,'RUNNING',?,false,false,?)",
                    requestId, userId, SCENE, clientRequestId, fingerprint, sessionId, model, created);
            Long userMessageId = insertMessage(requestId, sessionId, 1, userMessage, null, created);
            requireOne(jdbc.update("UPDATE ai_request_record SET user_message_id=? WHERE request_id=? AND status='RUNNING'", userMessageId, requestId));
            return get(requestId, false);
        });
    }

    public void markDispatched(AiRequestRecord request) {
        requireOne(jdbc.update("UPDATE ai_request_record SET dispatched=true,started_at=? WHERE request_id=? AND status='RUNNING' AND dispatched=false",
                now(), request.getRequestId()));
        request.setDispatched(true);
    }

    /** AI message and final status are one commit, including partial replies. */
    public AiRequestRecord finish(AiRequestRecord request, String status, String code, String message,
                                  String response, boolean saveResponse) {
        return transactions.execute(tx -> {
            boolean owned = lockOwnedSession(request.getUserId(), request.getSessionId());
            AiRequestRecord current = get(request.getRequestId(), true);
            if (!"RUNNING".equals(current.getStatus())) { return current; }
            String finalStatus = owned ? status : "FAILED";
            String finalCode = owned ? code : "410";
            String finalMessage = owned ? message : "会话已删除，回复未保存";
            Long aiMessageId = null;
            if (owned && saveResponse && response != null && !response.isEmpty()) {
                aiMessageId = insertMessage(current.getRequestId(), current.getSessionId(), 2, response, current.getAiModel(), now());
            }
            LocalDateTime finished = now();
            requireOne(jdbc.update("UPDATE ai_request_record SET status=?,ai_message_id=?,saved=?,error_code=?,error_message=?,finished_at=?,expires_at=? WHERE request_id=? AND status='RUNNING'",
                    finalStatus, aiMessageId, aiMessageId != null, finalCode, finalMessage, finished,
                    finished.plusHours(AiRequestRecord.RETENTION_HOURS), current.getRequestId()));
            return get(current.getRequestId(), false);
        });
    }

    public String replay(AiRequestRecord request) {
        return transactions.execute(tx -> {
            // Linearize replay against ownership/deletion, including empty stopped replies.
            if (!lockOwnedSession(request.getUserId(), request.getSessionId())) {
                throw new ReplyFailure("404", "会话不存在", request.getRequestId(), false);
            }
            if (request.getAiMessageId() == null) { return ""; }
            List<String> content = jdbc.query("SELECT content FROM consultation_message WHERE id=? AND request_id=? AND session_id=? AND sender_type=2",
                    (rs, n) -> rs.getString(1), request.getAiMessageId(), request.getRequestId(), request.getSessionId());
            if (content.isEmpty()) { throw new ReplyFailure("409", "原回复结果待确认，请勿自动重新生成", request.getRequestId(), false); }
            return content.get(0);
        });
    }

    /** No active local handle exists: never turn an orphan record into a fresh model call. */
    public AiRequestRecord markUnknown(AiRequestRecord request) {
        return finish(request, "UNKNOWN", "409", "请求结果待确认，请勿自动重新生成", null, false);
    }

    @PostConstruct
    public void recoverUnfinished() {
        // Deployment contract is a single JVM. Recovery must not run beside another instance.
        while (true) {
            List<AiRequestRecord> batch = jdbc.query("SELECT * FROM ai_request_record WHERE status='RUNNING' ORDER BY created_at,request_id LIMIT 100", ROW);
            if (batch.isEmpty()) { return; }
            for (AiRequestRecord request : batch) {
                if (!SCENE.equals(request.getScene())) {
                    // Only consultations have durable messages to reconcile after restart.
                    LocalDateTime finished = now();
                    jdbc.update("""
                            UPDATE ai_request_record SET status='UNKNOWN',saved=false,error_code='409',error_message=NULL,
                                finished_at=?,expires_at=? WHERE request_id=? AND scene=? AND status='RUNNING'
                            """, finished, finished.plusHours(AiRequestRecord.RETENTION_HOURS),
                            request.getRequestId(), request.getScene());
                    continue;
                }
                transactions.executeWithoutResult(tx -> {
                    lockOwnedSession(request.getUserId(), request.getSessionId());
                    AiRequestRecord current = get(request.getRequestId(), true);
                    if (!"RUNNING".equals(current.getStatus())) { return; }
                    List<Long> messages = jdbc.query("SELECT id FROM consultation_message WHERE request_id=? AND session_id=? AND sender_type=2 LIMIT 1",
                            (rs, n) -> rs.getLong(1), current.getRequestId(), current.getSessionId());
                    LocalDateTime finished = now();
                    boolean saved = !messages.isEmpty();
                    requireOne(jdbc.update("UPDATE ai_request_record SET status=?,ai_message_id=?,saved=?,error_code=?,error_message=?,finished_at=?,expires_at=? WHERE request_id=? AND status='RUNNING'",
                            saved ? "SUCCEEDED" : "UNKNOWN", saved ? messages.get(0) : null, saved, saved ? null : "409",
                            saved ? null : "应用已重启，原请求结果待确认，请勿自动重新生成", finished,
                            finished.plusHours(AiRequestRecord.RETENTION_HOURS), current.getRequestId()));
                });
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.ai.request-cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        jdbc.update("DELETE FROM ai_request_record WHERE status<>'RUNNING' AND finished_at IS NOT NULL AND expires_at<=? LIMIT 100", now());
    }

    private AiRequestRecord get(String requestId, boolean lock) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT * FROM ai_request_record WHERE request_id=?" + (lock ? " FOR UPDATE" : ""), ROW, requestId));
    }

    private boolean lockOwnedSession(Long userId, Long sessionId) {
        return !jdbc.query("SELECT id FROM consultation_session WHERE id=? AND user_id=? AND deleted=false FOR UPDATE",
                (rs, n) -> rs.getLong(1), sessionId, userId).isEmpty();
    }

    private Long insertMessage(String requestId, Long sessionId, int senderType, String content, String model, LocalDateTime created) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        requireOne(jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO consultation_message (request_id,session_id,sender_type,message_type,content,ai_model,created_at) VALUES (?,?,?,1,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, requestId); statement.setLong(2, sessionId); statement.setInt(3, senderType);
            statement.setString(4, content); statement.setString(5, model); statement.setObject(6, created);
            return statement;
        }, keys));
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private static void requireOne(int affected) {
        if (affected != 1) { throw new IllegalStateException("Request state transition was not committed"); }
    }
}
