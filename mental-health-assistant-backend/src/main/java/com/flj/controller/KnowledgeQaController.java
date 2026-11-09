package com.flj.controller;

import com.flj.common.Result;
import com.flj.rag.qa.KnowledgeQaService;
import com.flj.rag.qa.QaFailure;
import com.flj.rag.qa.QaResponse;
import com.flj.util.JwtTokenUtil;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeQaController {
    private final KnowledgeQaService service;

    public KnowledgeQaController(KnowledgeQaService service) {
        this.service = service;
    }

    @PostMapping("/qa")
    public ResponseEntity<Result<QaResponse>> ask(@RequestBody(required = false) QaRequest request) {
        String token = JwtTokenUtil.getCurrentToken();
        Long userId = token == null ? null : JwtTokenUtil.verifyToken(token).getClaim("userId").asLong();
        if (userId == null || userId <= 0) {
            throw new QaFailure("401", "FAILED", "请先登录", UUID.randomUUID().toString());
        }
        QaResponse result = service.ask(userId, request == null ? null : request.question(),
                request == null ? null : request.clientRequestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(result));
    }

    @ExceptionHandler(QaFailure.class)
    public ResponseEntity<Result<Map<String, String>>> failure(QaFailure error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(Integer.parseInt(error.getCode()))
                .cacheControl(CacheControl.noStore());
        if (error.getRetryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Integer.toString(error.getRetryAfterSeconds()));
        }
        return response.body(Result.error(error.getCode(), error.getMessage(),
                Map.of("requestId", error.getRequestId(), "status", error.getStatus())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Map<String, String>>> malformedRequest() {
        return failure(new QaFailure("400", "FAILED", "请求格式不正确", UUID.randomUUID().toString()));
    }

    public record QaRequest(String question, String clientRequestId) { }
}
