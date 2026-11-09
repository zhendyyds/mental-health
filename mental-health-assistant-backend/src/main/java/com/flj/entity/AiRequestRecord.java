package com.flj.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Durable generation metadata. Consultation alone links to persisted messages. */
@Data
public class AiRequestRecord {
    public static final long RETENTION_HOURS = 24;

    private String requestId;
    private Long userId;
    private String scene;
    private String clientRequestId;
    private String requestFingerprint;
    private Long sessionId;
    private String status;
    private Long userMessageId;
    private Long aiMessageId;
    private String aiModel;
    private boolean dispatched;
    private String errorCode;
    private String errorMessage;
    private boolean saved;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime expiresAt;
}
