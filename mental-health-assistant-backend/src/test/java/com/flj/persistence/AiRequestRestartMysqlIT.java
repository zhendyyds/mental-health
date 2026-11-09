package com.flj.persistence;

import com.flj.entity.AiRequestRecord;
import com.flj.service.AiRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;

/** The verification script runs each method in a different JVM around mysqld restart. */
class AiRequestRestartMysqlIT {
    @Test
    void writeCheckpoint() {
        MysqlVerification db = new MysqlVerification();
        db.reset();
        AiRequestRecord committed = db.create("cross-process-committed");
        AiRequestRecord unknown = db.create("cross-process-unknown");
        db.requests.markDispatched(committed);
        db.requests.markDispatched(unknown);
        db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content,request_id) VALUES (201,2,'durable fictional answer',?)", committed.getRequestId());
        assertEquals(2, db.count("ai_request_record"));
        assertEquals(3, db.count("consultation_message"));
    }

    @Test
    void recoverCheckpointInNewJvm() {
        MysqlVerification db = new MysqlVerification();
        assertEquals("RUNNING", db.requests.find(101L, "cross-process-committed").getStatus());
        assertEquals("RUNNING", db.requests.find(101L, "cross-process-unknown").getStatus());
        try (AnnotationConfigApplicationContext startup = new AnnotationConfigApplicationContext()) {
            startup.registerBean(JdbcTemplate.class, () -> db.jdbc);
            startup.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(db.dataSource));
            startup.register(AiRequestService.class);
            startup.refresh(); // Exercises production @PostConstruct before the bean becomes available.
            AiRequestService recoveredService = startup.getBean(AiRequestService.class);
            AiRequestRecord committed = recoveredService.find(101L, "cross-process-committed");
            assertEquals("SUCCEEDED", committed.getStatus());
            assertEquals("durable fictional answer", recoveredService.replay(committed));
            AiRequestRecord unknown = recoveredService.find(101L, "cross-process-unknown");
            assertEquals("UNKNOWN", unknown.getStatus());
            assertEquals("409", unknown.getErrorCode());
            assertEquals(3, db.count("consultation_message"));
        }
    }
}
