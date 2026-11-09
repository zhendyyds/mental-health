package com.flj.AiService;

/** Public, redacted failure metadata shared by generation and SSE observation. */
public class ReplyFailure extends RuntimeException {
    private final String code;
    private final String requestId;
    private final Boolean saved;

    public ReplyFailure(String code, String message, String requestId, boolean saved) {
        this(code, message, requestId, saved, null);
    }

    public ReplyFailure(String code, String message, String requestId, boolean saved, Throwable cause) {
        this(code, message, requestId, Boolean.valueOf(saved), cause);
    }

    public ReplyFailure(String code, String message, String requestId, Boolean saved, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
        this.saved = saved;
    }

    public String getCode() { return code; }
    public String getRequestId() { return requestId; }
    public boolean isSaved() { return Boolean.TRUE.equals(saved); }
    public Boolean getSaved() { return saved; }
}
