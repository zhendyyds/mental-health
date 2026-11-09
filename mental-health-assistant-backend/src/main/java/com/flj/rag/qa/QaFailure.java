package com.flj.rag.qa;

/** The HTTP response contains only these safe, application-defined details. */
public final class QaFailure extends RuntimeException {
    private final String code;
    private final String status;
    private final String requestId;
    private final int retryAfterSeconds;

    public QaFailure(String code, String status, String message, String requestId) {
        this(code, status, message, requestId, 0);
    }

    public QaFailure(String code, String status, String message, String requestId, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.status = status;
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() { return code; }
    public String getStatus() { return status; }
    public String getRequestId() { return requestId; }
    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
