package com.flj.AiService.governance;

import java.util.UUID;

/** Only redacted, locally generated messages may be returned to a caller. */
public class AiCallRejectedException extends RuntimeException {
    private final String code;
    private final String requestId;
    private final int retryAfterSeconds;

    public AiCallRejectedException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.requestId = UUID.randomUUID().toString();
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public String getCode() { return code; }
    public String getRequestId() { return requestId; }
    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
