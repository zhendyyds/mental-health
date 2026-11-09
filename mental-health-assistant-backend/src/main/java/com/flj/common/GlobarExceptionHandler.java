package com.flj.common;

import com.flj.exception.BusinessException;
import com.flj.AiService.AnalysisCallException;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.DTO.command.ConsultationStreamDTO;
import com.flj.rag.RagIndexException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;
import java.util.Map;

@RestControllerAdvice
public class GlobarExceptionHandler {
    @ExceptionHandler(RagIndexException.class)
    public ResponseEntity<Result<Void>> handleRagFailure(RagIndexException error) {
        return ResponseEntity.status(httpStatus(error.getCode()))
                .body(Result.error(error.getCode(), error.getMessage(), null));
    }

    @ExceptionHandler(AiCallRejectedException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleAiRejection(AiCallRejectedException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(httpStatus(error.getCode()));
        if (error.getRetryAfterSeconds() > 0) response.header("Retry-After", String.valueOf(error.getRetryAfterSeconds()));
        return response.body(Result.error(error.getCode(), error.getMessage(), Map.of("requestId", error.getRequestId())));
    }

    @ExceptionHandler(AnalysisCallException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleAnalysisFailure(AnalysisCallException error) {
        return ResponseEntity.status(httpStatus(error.getCode())).body(Result.error(error.getCode(), error.getMessage(),
                Map.of("requestId", error.getRequestId(), "analysisStatus", error.getAnalysisStatus())));
    }

    private int httpStatus(String code) {
        return switch (code) {
            case "400" -> 400;
            case "401" -> 401;
            case "429" -> 429;
            case "502" -> 502;
            case "503" -> 503;
            case "504" -> 504;
            default -> 500;
        };
    }

    // 处理参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handlerException(MethodArgumentNotValidException e) {
        // 处理异常数据的处理
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        if (e.getBindingResult().getTarget() instanceof ConsultationStreamDTO) {
            return ResponseEntity.badRequest().body(Result.error("400", message,
                    Map.of("requestId", java.util.UUID.randomUUID().toString(), "saved", false, "status", "FAILED")));
        }
        return Result.error(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMsg(), message);
    }

    // 处理业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        // 如果异样携带额外的数据
        if (e.getData() != null) {
            return Result.error(e.getCode(), e.getMessage(), e.getData());
        }
        return Result.error(e.getCode(), e.getMessage(), null);
    }

    // 处理上传超限：multipart解析阶段即拒绝（早于业务层校验），需返回友好提示而非连接重置
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<String> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return Result.error(ResultCode.FILE_SIZE_EXCEEDED.getCode(), "图片大小不能超过5MB", null);
    }
}
