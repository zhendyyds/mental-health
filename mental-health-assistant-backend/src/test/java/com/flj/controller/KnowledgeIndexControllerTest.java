package com.flj.controller;

import com.flj.DTO.response.UserLoginResponseDTO;
import com.flj.common.GlobarExceptionHandler;
import com.flj.config.JwtConfig;
import com.flj.config.SecurityConfig;
import com.flj.rag.IndexJob;
import com.flj.rag.KnowledgeIndexRepository;
import com.flj.rag.RagIndexException;
import com.flj.rag.RagIndexSettings;
import com.flj.rag.RagRetrievalService;
import com.flj.service.CategoryService;
import com.flj.service.KnowledgeArticleService;
import com.flj.service.UserService;
import com.flj.util.JwtTokenUtil;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeIndexControllerTest {
    private static final String ROOT = "/api/admin/knowledge";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final KnowledgeIndexRepository jobs = mock(KnowledgeIndexRepository.class);
    private final RagRetrievalService retrieval = mock(RagRetrievalService.class);
    private final RagIndexSettings settings = mock(RagIndexSettings.class);

    @BeforeEach
    void configureMvc() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        UserService users = mock(UserService.class);
        when(users.getUserById(7L)).thenReturn(UserLoginResponseDTO.UserDetailResponseDTO.builder().id(7L).status(1).build());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("userService", users);
            factory.registerSingleton("categories", mock(CategoryService.class));
            factory.registerSingleton("articles", mock(KnowledgeArticleService.class));
            factory.registerSingleton("indexJobs", jobs);
            factory.registerSingleton("retrieval", retrieval);
            factory.registerSingleton("ragSettings", settings);
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
    void normalUserCannotReachIndexManagementOrRetrievalDebug() throws Exception {
        mvc.perform(get(ROOT + "/index-jobs")).andExpect(status().isUnauthorized());
        mvc.perform(asRole(get(ROOT + "/index-jobs"), 1)).andExpect(status().isForbidden());
        mvc.perform(asRole(post(ROOT + "/articles/fixture/reindex"), 1)).andExpect(status().isForbidden());
        mvc.perform(asRole(post(ROOT + "/index-jobs/123/retry"), 1)).andExpect(status().isForbidden());
        mvc.perform(asRole(debug(), 1)).andExpect(status().isForbidden());
        verifyNoInteractions(jobs, retrieval);
    }

    @Test
    void adminCanInspectJobsButCannotQueueWhileIndexingIsDisabled() throws Exception {
        IndexJob job = new IndexJob(17L, "fixture", 2, "v1", "generation", "BUILD", "RUNNING",
                1, true, null, "internal-worker", "internal-lease", null, null, null, null, null);
        when(jobs.listJobs(null, 50)).thenReturn(List.of(job));
        mvc.perform(asRole(get(ROOT + "/index-jobs"), 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data[0].leaseOwner").doesNotExist());
        mvc.perform(asRole(post(ROOT + "/articles/fixture/reindex"), 2)).andExpect(jsonPath("$.code").value("503"));
        mvc.perform(asRole(post(ROOT + "/index-jobs/123/retry"), 2)).andExpect(jsonPath("$.code").value("503"));
        verify(jobs).listJobs(null, 50);
    }

    @Test
    void debugDistinguishesNoEligibleEvidenceFromUnavailableProvider() throws Exception {
        when(retrieval.retrieve(7L, "虚构问题")).thenReturn(List.of());
        mvc.perform(asRole(debug(), 2)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        when(retrieval.retrieve(7L, "虚构问题")).thenThrow(new RagIndexException("502", "向量服务暂不可用"));
        mvc.perform(asRole(debug(), 2)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("502"));
    }

    private MockHttpServletRequestBuilder debug() {
        return post(ROOT + "/retrieval/debug").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"虚构问题\"}");
    }

    private MockHttpServletRequestBuilder asRole(MockHttpServletRequestBuilder request, int role) {
        return request.header("token", JwtTokenUtil.generateToken(7L, "fixture-user", role));
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class WebConfig {
        @Bean AdminKnowledgeController controller() { return new AdminKnowledgeController(); }
        @Bean GlobarExceptionHandler exceptionHandler() { return new GlobarExceptionHandler(); }
        @Bean JwtTokenUtil jwtTokenUtil() { return new JwtTokenUtil(); }
        @Bean JwtConfig jwtConfig() {
            JwtConfig config = new JwtConfig();
            config.setSecret("index-controller-test-fixture-signing-key");
            config.setExpiration(60000);
            return config;
        }
    }
}
