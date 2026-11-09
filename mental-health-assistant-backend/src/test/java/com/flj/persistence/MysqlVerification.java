package com.flj.persistence;

import com.flj.entity.AiRequestRecord;
import com.flj.service.AiRequestService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

/** Only the opt-in db/verify-mysql.ps1 fixture may enable these destructive tests. */
final class MysqlVerification {
    final DriverManagerDataSource dataSource;
    final JdbcTemplate jdbc;
    final AiRequestService requests;

    MysqlVerification() {
        String url = System.getenv().getOrDefault("BATCH3_JDBC_URL", "");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/batch3_[a-z0-9_]+\\?.*")) {
            throw new IllegalStateException("Use db/verify-mysql.ps1 with its isolated target MySQL fixture");
        }
        dataSource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(dataSource);
        String marker = jdbc.queryForObject("SELECT marker FROM batch3_verification_guard", String.class);
        if (!"isolated-target-datadir".equals(marker)) {
            throw new IllegalStateException("Verification database marker is absent");
        }
        requests = newService();
    }

    AiRequestService newService() {
        return new AiRequestService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS batch3_fail_message");
        jdbc.execute("DROP TRIGGER IF EXISTS batch3_fail_final");
        for (String table : new String[]{"ai_request_record", "consultation_message", "consultation_session",
                "emotion_diary", "knowledge_article", "knowledge_category", "user"}) {
            jdbc.update("DELETE FROM `" + table + "`");
        }
        jdbc.update("INSERT INTO `user` (id,username,email,password) VALUES (101,'fictional_a','fictional_a@example.invalid','not-a-login-password'),(102,'fictional_b','fictional_b@example.invalid','not-a-login-password')");
        jdbc.update("INSERT INTO consultation_session (id,user_id,session_title) VALUES (201,101,'Fictional verification A'),(202,102,'Fictional verification B')");
    }

    AiRequestRecord create(String clientId) {
        return requests.create(UUID.randomUUID().toString(), 101L, clientId, 201L, "a".repeat(64), "fictional-model", "虚构验收问题");
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
    }

    void script(String relative) throws Exception {
        Path root = Path.of(System.getenv().getOrDefault("BATCH3_BACKEND_DIR", ".")).toAbsolutePath().normalize();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(relative)));
        }
    }
}
