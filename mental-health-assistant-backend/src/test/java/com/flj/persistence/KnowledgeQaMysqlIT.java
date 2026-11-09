package com.flj.persistence;

import com.flj.entity.AiRequestRecord;
import com.flj.rag.KnowledgeQaRequestRepository;
import com.flj.service.AiRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests using the isolated fixture from verify-mysql.ps1 -Suite KnowledgeQa. */
class KnowledgeQaMysqlIT {
    private static final String MIGRATION = "db/migrations/20261107_allow_knowledge_qa_requests.sql";
    private static final String FINGERPRINT = "a".repeat(64);

    private MysqlVerification db;
    private KnowledgeQaRequestRepository qa;

    @BeforeEach
    void fixture() {
        db = new MysqlVerification();
        db.reset();
        qa = new KnowledgeQaRequestRepository(db.jdbc);
    }

    @Test
    void migrationRetainsLegacyColumnAttributesAndConsultationReplay() throws Exception {
        // Restore the old non-null shape in this empty, guarded fixture only.
        db.jdbc.execute("ALTER TABLE ai_request_record MODIFY COLUMN session_id BIGINT UNSIGNED NOT NULL DEFAULT 17 COMMENT 'Legacy session reference'");
        AiRequestRecord consultation = db.create("legacy-consultation");
        db.requests.markDispatched(consultation);
        db.requests.finish(consultation, "SUCCEEDED", null, null, "Fictional legacy answer", true);
        assertThrows(DataIntegrityViolationException.class, () -> create(101, "qa-before-migration"));

        db.script(MIGRATION);
        AiRequestRecord independent = create(101, "qa-after-migration");
        db.script(MIGRATION);

        Map<String, Object> column = db.jdbc.queryForMap("""
                SELECT COLUMN_TYPE,COLUMN_DEFAULT,COLUMN_COMMENT,IS_NULLABLE
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                    AND TABLE_NAME='ai_request_record' AND COLUMN_NAME='session_id'
                """);
        assertEquals("bigint unsigned", column.get("COLUMN_TYPE"));
        assertEquals("17", column.get("COLUMN_DEFAULT"));
        assertEquals("Legacy session reference", column.get("COLUMN_COMMENT"));
        assertEquals("YES", column.get("IS_NULLABLE"));
        AiRequestRecord legacy = db.requests.find(101L, "legacy-consultation");
        assertEquals(201L, legacy.getSessionId());
        assertEquals("Fictional legacy answer", db.requests.replay(legacy));
        assertEquals(independent.getRequestId(), qa.find(101, "qa-after-migration").getRequestId());
        assertMetadataOnly(independent.getRequestId());
        assertEquals(2L, db.count("consultation_message"));
    }

    @Test
    void concurrentSameIdCreatesOneMetadataRecordAndRetainsOriginalFingerprint() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<AiRequestRecord> winners = new ArrayList<>();
        try {
            List<Future<AiRequestRecord>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        return create(101, "concurrent-knowledge-id");
                    } catch (DuplicateKeyException duplicate) {
                        return null;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<AiRequestRecord> attempt : attempts) {
                AiRequestRecord result = attempt.get(15, TimeUnit.SECONDS);
                if (result != null) winners.add(result);
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, winners.size());
        assertEquals(1L, db.count("ai_request_record"));
        assertEquals(0L, db.count("consultation_message"));
        assertThrows(DuplicateKeyException.class, () -> qa.create(101, "concurrent-knowledge-id", "b".repeat(64),
                "fictional-model", UUID.randomUUID().toString()));
        AiRequestRecord durable = qa.find(101, "concurrent-knowledge-id");
        assertEquals(winners.get(0).getRequestId(), durable.getRequestId());
        assertEquals(FINGERPRINT, durable.getRequestFingerprint());
        assertEquals("RUNNING", durable.getStatus());
    }

    @Test
    void clientIdIsIndependentAcrossUsersAndScenesAndCasCannotModifyConsultation() {
        String clientId = "same-id-separate-scope";
        AiRequestRecord first = create(101, clientId);
        AiRequestRecord second = create(102, clientId);
        AiRequestRecord consultation = db.create(clientId);

        assertEquals(first.getRequestId(), qa.find(101, clientId).getRequestId());
        assertEquals(second.getRequestId(), qa.find(102, clientId).getRequestId());
        assertNull(qa.find(999, clientId));
        assertEquals(consultation.getRequestId(), db.requests.find(101L, clientId).getRequestId());
        assertEquals(1, db.requests.findByRequestIds(List.of(first.getRequestId(), second.getRequestId(), consultation.getRequestId())).size());
        assertFalse(qa.markDispatched(consultation));
        assertFalse(qa.finish(consultation, "UNKNOWN", "409"));
        assertEquals("RUNNING", db.requests.find(101L, clientId).getStatus());
        assertFalse(db.requests.find(101L, clientId).isDispatched());
        assertEquals(3L, db.count("ai_request_record"));
    }

    @Test
    void terminalCasStoresOnlyApprovedMetadataAndRetainsItFor24Hours() {
        AiRequestRecord record = create(101, "qa-terminal-metadata");
        assertTrue(qa.markDispatched(record));
        assertTrue(record.isDispatched());
        assertFalse(qa.markDispatched(record));
        assertThrows(IllegalArgumentException.class, () -> qa.finish(record, "FAILED", "raw upstream fixture message"));
        assertEquals("RUNNING", qa.find(101, record.getClientRequestId()).getStatus());
        assertTrue(qa.finish(record, "FAILED", "SRC_409"));
        assertFalse(qa.finish(record, "SUCCEEDED", null));

        AiRequestRecord durable = qa.find(101, record.getClientRequestId());
        assertEquals("FAILED", durable.getStatus());
        assertEquals("SRC_409", durable.getErrorCode());
        assertEquals(durable.getFinishedAt().plusHours(24), durable.getExpiresAt());
        assertEquals(FINGERPRINT, durable.getRequestFingerprint());
        assertMetadataOnly(record.getRequestId());
        assertEquals(0L, db.count("consultation_message"));
    }

    @Test
    void terminalExpiryAllowsANewRecordButNeverDeletesRunningRequests() {
        AiRequestRecord expiredOnRead = create(101, "qa-expired-on-read");
        AiRequestRecord expiredBySweep = create(101, "qa-expired-by-sweep");
        AiRequestRecord running = create(101, "qa-retain-running");
        assertTrue(qa.finish(expiredOnRead, "SUCCEEDED", null));
        assertTrue(qa.finish(expiredBySweep, "UNKNOWN", "409"));
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
        db.jdbc.update("UPDATE ai_request_record SET expires_at=?", past);

        assertNull(qa.find(101, expiredOnRead.getClientRequestId()));
        AiRequestRecord replacement = create(101, expiredOnRead.getClientRequestId());
        assertNotEquals(expiredOnRead.getRequestId(), replacement.getRequestId());
        db.requests.cleanupExpired();
        assertEquals(2L, db.count("ai_request_record"));
        assertNull(qa.find(101, expiredBySweep.getClientRequestId()));
        assertEquals(running.getRequestId(), qa.find(101, running.getClientRequestId()).getRequestId());
    }

    @Test
    void startupRecoversKnowledgeRequestsWhenConsultationTablesAreUnavailable() {
        AiRequestRecord running = create(101, "qa-startup-running");
        AiRequestRecord completed = create(101, "qa-startup-complete");
        assertTrue(qa.markDispatched(running));
        assertTrue(qa.finish(completed, "SUCCEEDED", null));

        // Hide the consultation tables temporarily to prove QA recovery never queries them.
        db.jdbc.execute("RENAME TABLE consultation_session TO qa_fixture_hidden_session, consultation_message TO qa_fixture_hidden_message");
        try (AnnotationConfigApplicationContext startup = new AnnotationConfigApplicationContext()) {
            startup.registerBean(JdbcTemplate.class, () -> db.jdbc);
            startup.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(db.dataSource));
            startup.register(AiRequestService.class);
            startup.refresh();
            AiRequestRecord recovered = qa.find(101, running.getClientRequestId());
            assertEquals("UNKNOWN", recovered.getStatus());
            assertEquals("409", recovered.getErrorCode());
            assertEquals(recovered.getFinishedAt().plusHours(24), recovered.getExpiresAt());
            assertEquals("SUCCEEDED", qa.find(101, completed.getClientRequestId()).getStatus());
            assertMetadataOnly(running.getRequestId());
        } finally {
            db.jdbc.execute("RENAME TABLE qa_fixture_hidden_session TO consultation_session, qa_fixture_hidden_message TO consultation_message");
        }
    }

    @Test
    void mixedStartupStillReconcilesCommittedConsultationMessages() {
        AiRequestRecord knowledge = create(101, "qa-mixed-startup");
        AiRequestRecord consultation = db.create("consultation-mixed-startup");
        db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content,request_id) VALUES (201,2,'Fictional committed answer',?)",
                consultation.getRequestId());

        db.newService().recoverUnfinished();
        AiRequestRecord restored = db.requests.find(101L, consultation.getClientRequestId());
        assertEquals("SUCCEEDED", restored.getStatus());
        assertTrue(restored.isSaved());
        assertEquals("Fictional committed answer", db.requests.replay(restored));
        assertEquals("UNKNOWN", qa.find(101, knowledge.getClientRequestId()).getStatus());
        assertEquals(2L, db.count("consultation_message"));
    }

    private AiRequestRecord create(long userId, String clientId) {
        return qa.create(userId, clientId, FINGERPRINT, "fictional-model", UUID.randomUUID().toString());
    }

    private void assertMetadataOnly(String requestId) {
        Map<String, Object> row = db.jdbc.queryForMap("""
                SELECT session_id,user_message_id,ai_message_id,error_message,saved
                FROM ai_request_record WHERE request_id=?
                """, requestId);
        assertNull(row.get("session_id"));
        assertNull(row.get("user_message_id"));
        assertNull(row.get("ai_message_id"));
        assertNull(row.get("error_message"));
        assertEquals(Boolean.FALSE, row.get("saved"));
    }
}
