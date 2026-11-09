package com.flj.controller;

import com.flj.DTO.response.UserLoginResponseDTO;
import com.flj.config.JwtConfig;
import com.flj.config.SecurityConfig;
import com.flj.rag.qa.KnowledgeQaService;
import com.flj.rag.qa.QaCitation;
import com.flj.rag.qa.QaFailure;
import com.flj.rag.qa.QaResponse;
import com.flj.service.UserService;
import com.flj.util.JwtTokenUtil;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeQaControllerTest {
    private static final String BODY = "{\"question\":\"虚构问题\",\"clientRequestId\":\"fixture-id-1\"}";
    private final KnowledgeQaService service = mock(KnowledgeQaService.class);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        UserService users = mock(UserService.class);
        when(users.getUserById(7L)).thenReturn(UserLoginResponseDTO.UserDetailResponseDTO.builder().id(7L).status(1).build());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("userService", users);
            factory.registerSingleton("knowledgeQaService", service);
        });
        context.register(WebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void closeContext() {
        if (context != null) { context.close(); }
    }

    @Test
    void anonymousRequestCannotReachQa() throws Exception {
        mvc.perform(request(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedRequestUsesTokenIdentityAndReturnsUncacheableCitations() throws Exception {
        when(service.ask(7L, "虚构问题", "fixture-id-1")).thenReturn(new QaResponse("request-7", "原文", true,
                List.of(new QaCitation("S1", "article-7", "虚构资料", 4, "原文"))));
        mvc.perform(authenticated(BODY)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("200")).andExpect(jsonPath("$.data.requestId").value("request-7"))
                .andExpect(jsonPath("$.data.citations[0].revision").value(4))
                .andExpect(jsonPath("$.data.citations[0].excerpt").value("原文"));
        verify(service).ask(7L, "虚构问题", "fixture-id-1");
    }

    @Test
    void rateLimitPreservesHttpStatusRetryHeaderAndSafeRequestData() throws Exception {
        when(service.ask(7L, "虚构问题", "fixture-id-1"))
                .thenThrow(new QaFailure("429", "FAILED", "请求过于频繁", "request-7", 23));
        mvc.perform(authenticated(BODY)).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "23"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("429")).andExpect(jsonPath("$.data.requestId").value("request-7"))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void malformedBodyReturnsHttp400WithoutCallingTheService() throws Exception {
        mvc.perform(authenticated("{\"question\":" )).andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("400")).andExpect(jsonPath("$.data.requestId").isNotEmpty());
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder request(String body) {
        return post("/api/knowledge/qa").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder authenticated(String body) {
        return request(body).header("token", JwtTokenUtil.generateToken(7L, "qa-fixture-user", 1));
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class WebConfig {
        @Bean KnowledgeQaController controller(KnowledgeQaService service) { return new KnowledgeQaController(service); }
        @Bean JwtTokenUtil jwtTokenUtil() { return new JwtTokenUtil(); }
        @Bean JwtConfig jwtConfig() {
            JwtConfig config = new JwtConfig();
            config.setSecret("qa-controller-fixture-key-no-production-use");
            config.setExpiration(60_000);
            return config;
        }
    }
}
