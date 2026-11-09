package com.flj.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

@RestController
@RequestMapping("/internal/health")
public class DeploymentHealthController {
    private final JdbcTemplate checks;

    public DeploymentHealthController(DataSource dataSource) {
        checks = new JdbcTemplate(dataSource);
        checks.setQueryTimeout(2);
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, String>> live() { return status(true); }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            checks.execute("SELECT request_id FROM ai_request_record WHERE 1=0");
            checks.execute("SELECT content_revision FROM knowledge_article WHERE 1=0");
            return status(true);
        } catch (DataAccessException unavailable) {
            return status(false);
        }
    }

    private static ResponseEntity<Map<String, String>> status(boolean up) {
        return ResponseEntity.status(up ? 200 : 503).cacheControl(CacheControl.noStore())
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
