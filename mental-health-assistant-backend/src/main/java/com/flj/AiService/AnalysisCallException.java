package com.flj.AiService;

/** Redacted synchronous-analysis failure; safe metadata for the HTTP error mapper. */
public class AnalysisCallException extends RuntimeException {
    private final String code;
    private final String requestId;
    private final String analysisStatus;

    public AnalysisCallException(String code, String message, String requestId, String analysisStatus) {
        this(code, message, requestId, analysisStatus, null);
    }

    public AnalysisCallException(String code, String message, String requestId, String analysisStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
        this.analysisStatus = analysisStatus;
    }

    public String getCode() { return code; }
    public String getRequestId() { return requestId; }
    public String getAnalysisStatus() { return analysisStatus; }
}
