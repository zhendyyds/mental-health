package com.flj.rag;

import java.time.LocalDateTime;

public record IndexJob(long id, String articleId, long revision, String configVersion, String generation,
                       String operation, String status, int attemptCount, boolean retryable, LocalDateTime nextRetryAt,
                       String leaseOwner, String leaseToken, LocalDateTime leaseUntil, String errorCode,
                       LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime finishedAt) {
}
