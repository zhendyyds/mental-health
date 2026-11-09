package com.flj.rag;

/** A redacted error safe for job state, logs, and the administrative API. */
public class RagIndexException extends RuntimeException {
    private final String code;
    private final String errorCode;
    private final boolean retryable;

    public RagIndexException(String code, String message) {
        this(code, "RAG_UNAVAILABLE", message, false);
    }

    public RagIndexException(String code, String errorCode, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public String getErrorCode() { return errorCode; }
    public boolean isRetryable() { return retryable; }
}
