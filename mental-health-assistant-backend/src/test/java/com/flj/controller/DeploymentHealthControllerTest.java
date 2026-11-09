package com.flj.controller;

import com.flj.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentHealthControllerTest {
    @Test
    void livenessIsIndependentAndDatabaseOutageIsAReal503WithoutDetails() throws Exception {
        DataSource database = mock(DataSource.class);
        when(database.getConnection()).thenThrow(new SQLException("fixture private database details"));
        var mvc = MockMvcBuilders.standaloneSetup(new DeploymentHealthController(database)).build();

        mvc.perform(get("/internal/health/live")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        verify(database, never()).getConnection();
        mvc.perform(get("/internal/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", true));
        verify(database).getConnection();
    }

    @Test
    void readinessChecksCoreSchemaWithTimeoutAndProbePathsAreExact() throws Exception {
        DataSource database = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        var mvc = MockMvcBuilders.standaloneSetup(new DeploymentHealthController(database)).build();

        mvc.perform(get("/internal/health/ready")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        verify(statement, times(2)).setQueryTimeout(2);
        verify(statement).execute("SELECT request_id FROM ai_request_record WHERE 1=0");
        verify(statement).execute("SELECT content_revision FROM knowledge_article WHERE 1=0");
        assertTrue(SecurityConfig.isPublicPATH("/internal/health/live"));
        assertTrue(SecurityConfig.isPublicPATH("/internal/health/ready"));
        assertFalse(SecurityConfig.isPublicPATH("/internal/health/ready/details"));
    }
}
