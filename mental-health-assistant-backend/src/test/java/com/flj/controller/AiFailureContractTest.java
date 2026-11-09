package com.flj.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.AnalysisCallException;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.common.GlobarExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AiFailureContractTest {
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailureProbe())
                .setControllerAdvice(new GlobarExceptionHandler()).build();
    }

    @Test
    void quotaRejectionUsesReal429RetryAfterAndStringCode() throws Exception {
        MvcResult response = mvc.perform(get("/failure/quota")).andReturn();
        JsonNode body = json.readTree(response.getResponse().getContentAsByteArray());
        assertEquals(429, response.getResponse().getStatus());
        assertEquals("50", response.getResponse().getHeader("Retry-After"));
        assertTrue(body.path("code").isTextual());
        assertEquals("429", body.path("code").asText());
        assertFalse(body.at("/data/requestId").asText().isBlank());
    }

    @Test
    void concurrencyRejectionDoesNotInventRetryAfter() throws Exception {
        MvcResult response = mvc.perform(get("/failure/busy")).andReturn();
        assertEquals(429, response.getResponse().getStatus());
        assertNull(response.getResponse().getHeader("Retry-After"));
    }

    @Test
    void inputLimitUses400AndCarriesCorrelation() throws Exception {
        MvcResult response = mvc.perform(get("/failure/input")).andReturn();
        assertEquals(400, response.getResponse().getStatus());
        assertEquals("400", json.readTree(response.getResponse().getContentAsByteArray()).path("code").asText());
    }

    @Test
    void analysisDeadlineUses504AndDoesNotExposeUpstreamDetails() throws Exception {
        MvcResult response = mvc.perform(get("/failure/deadline")).andReturn();
        JsonNode body = json.readTree(response.getResponse().getContentAsByteArray());
        assertEquals(504, response.getResponse().getStatus());
        assertEquals("analysis-request", body.at("/data/requestId").asText());
        assertEquals("TIMED_OUT", body.at("/data/analysisStatus").asText());
        assertFalse(body.toString().contains("private-upstream-content"));
    }

    @Test
    void failedAnalysisUses502() throws Exception {
        MvcResult response = mvc.perform(get("/failure/upstream")).andReturn();
        assertEquals(502, response.getResponse().getStatus());
        assertEquals("FAILED", json.readTree(response.getResponse().getContentAsByteArray())
                .at("/data/analysisStatus").asText());
    }

    @RestController
    static class FailureProbe {
        @GetMapping("/failure/quota") Object quota() { throw new AiCallRejectedException("429", "请求过于频繁", 50); }
        @GetMapping("/failure/busy") Object busy() { throw new AiCallRejectedException("429", "服务繁忙", 0); }
        @GetMapping("/failure/input") Object input() { throw new AiCallRejectedException("400", "输入超过预算", 0); }
        @GetMapping("/failure/deadline") Object deadline() {
            throw new AnalysisCallException("504", "分析超时", "analysis-request", "TIMED_OUT",
                    new IllegalStateException("private-upstream-content"));
        }
        @GetMapping("/failure/upstream") Object upstream() {
            throw new AnalysisCallException("502", "分析暂不可用", "analysis-request", "FAILED");
        }
    }
}
