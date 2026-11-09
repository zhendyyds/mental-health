package com.flj.rag;

import com.flj.entity.AiRequestRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Metadata-only ledger for independent knowledge questions. */
@Repository
public class KnowledgeQaRequestRepository {
    public static final String SCENE = "knowledge-qa";
    private static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final RowMapper<AiRequestRecord> ROW = (row, number) -> {
        AiRequestRecord record = new AiRequestRecord();
        record.setRequestId(row.getString("request_id"));
        record.setUserId(row.getLong("user_id"));
        record.setScene(SCENE);
        record.setClientRequestId(row.getString("client_request_id"));
        record.setRequestFingerprint(row.getString("request_fingerprint"));
        record.setStatus(row.getString("status"));
        record.setAiModel(row.getString("ai_model"));
        record.setDispatched(row.getBoolean("dispatched"));
        record.setErrorCode(row.getString("error_code"));
        record.setCreatedAt(row.getObject("created_at", LocalDateTime.class));
        record.setStartedAt(row.getObject("started_at", LocalDateTime.class));
        record.setFinishedAt(row.getObject("finished_at", LocalDateTime.class));
        record.setExpiresAt(row.getObject("expires_at", LocalDateTime.class));
        return record;
    };

    private final JdbcTemplate jdbc;

    public KnowledgeQaRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AiRequestRecord find(long userId, String clientRequestId) {
        List<AiRequestRecord> rows = jdbc.query("""
                SELECT request_id,user_id,client_request_id,request_fingerprint,status,ai_model,dispatched,
                    error_code,created_at,started_at,finished_at,expires_at
                FROM ai_request_record WHERE user_id=? AND scene=? AND client_request_id=?
                """, ROW, userId, SCENE, clientRequestId);
        if (rows.isEmpty()) return null;
        AiRequestRecord record = rows.get(0);
        LocalDateTime now = now();
        if (!"RUNNING".equals(record.getStatus()) && record.getFinishedAt() != null
                && record.getExpiresAt() != null && !record.getExpiresAt().isAfter(now)) {
            jdbc.update("""
                    DELETE FROM ai_request_record WHERE request_id=? AND user_id=? AND scene=?
                        AND status<>'RUNNING' AND finished_at IS NOT NULL AND expires_at<=?
                    """, record.getRequestId(), userId, SCENE, now);
            return null;
        }
        return record;
    }

    /** A duplicate key lets the caller observe the winner without dispatching a model. */
    public AiRequestRecord create(long userId, String clientRequestId, String fingerprint, String model, String requestId) {
        if (userId < 1 || clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9_-]{8,128}")
                || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")
                || model != null && !model.matches("[A-Za-z0-9_./:-]{1,100}")
                || requestId == null || !requestId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid knowledge request metadata");
        }
        LocalDateTime created = now();
        jdbc.update("""
                INSERT INTO ai_request_record (request_id,user_id,scene,client_request_id,request_fingerprint,
                    session_id,status,ai_model,dispatched,saved,created_at)
                VALUES (?,?,?,?,?,NULL,'RUNNING',?,false,false,?)
                """, requestId, userId, SCENE, clientRequestId, fingerprint, model, created);
        AiRequestRecord record = new AiRequestRecord();
        record.setRequestId(requestId);
        record.setUserId(userId);
        record.setScene(SCENE);
        record.setClientRequestId(clientRequestId);
        record.setRequestFingerprint(fingerprint);
        record.setStatus("RUNNING");
        record.setAiModel(model);
        record.setCreatedAt(created);
        return record;
    }

    public boolean markDispatched(AiRequestRecord record) {
        LocalDateTime started = now();
        boolean changed = jdbc.update("""
                UPDATE ai_request_record SET dispatched=true,started_at=?
                WHERE request_id=? AND user_id=? AND scene=? AND status='RUNNING' AND dispatched=false
                """, started, record.getRequestId(), record.getUserId(), SCENE) == 1;
        if (changed) {
            record.setDispatched(true);
            record.setStartedAt(started);
        }
        return changed;
    }

    public boolean finish(AiRequestRecord record, String status, String errorCode) {
        boolean valid = switch (status) {
            case "SUCCEEDED" -> errorCode == null;
            case "UNKNOWN" -> "409".equals(errorCode);
            case "FAILED" -> "SRC_409".equals(errorCode) || (errorCode != null && errorCode.matches("[45][0-9]{2}"));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Invalid knowledge request terminal state");
        LocalDateTime finished = now();
        return jdbc.update("""
                UPDATE ai_request_record SET status=?,error_code=?,finished_at=?,expires_at=?
                WHERE request_id=? AND user_id=? AND scene=? AND status='RUNNING'
                """, status, errorCode, finished, finished.plusHours(AiRequestRecord.RETENTION_HOURS),
                record.getRequestId(), record.getUserId(), SCENE) == 1;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(LEDGER_ZONE);
    }
}
