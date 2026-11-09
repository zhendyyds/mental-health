package com.flj.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.flj.AiService.ReplyFailure;
import com.flj.entity.AiRequestRecord;
import com.flj.mapper.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL tests. Default mvn test deliberately does not discover *IT. */
class AiRequestMysqlIT {
    private MysqlVerification db;

    @BeforeEach
    void fixture() {
        db = new MysqlVerification();
        db.reset();
    }

    @Test
    void baselineRepeatsWithoutChangingRowsAndAllProductionMappersReadIt() throws Exception {
        db.jdbc.update("INSERT INTO knowledge_category (id,category_name) VALUES (301,'虚构分类')");
        db.jdbc.update("INSERT INTO knowledge_article (id,title,content,category_id,author_id) VALUES ('00000000-0000-0000-0000-000000000001','虚构文章','<p>fixture</p>',301,101)");
        db.jdbc.update("INSERT INTO emotion_diary (user_id,diary_date,mood_score,dominant_emotion) VALUES (101,'2026-11-06',6,'平静')");
        db.create("baseline-preservation");
        db.script("db/baseline/20261106_schema.sql");
        db.script("db/baseline/20261106_schema.sql");
        assertEquals(2, db.count("user"));
        assertEquals(1, db.count("consultation_message"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("real-mysql-baseline", new JdbcTransactionFactory(), db.dataSource));
        for (Class<?> mapper : List.of(UserMapper.class, ConsultationSessionMapper.class, ConsultationMessageMapper.class,
                CategoryMapper.class, ArticleMapper.class, EmotionDiaryMapper.class)) { configuration.addMapper(mapper); }
        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            assertEquals("fictional_a", session.getMapper(UserMapper.class).selectById(101L).getUsername());
            assertFalse(session.getMapper(ConsultationSessionMapper.class).selectById(201L).getDeleted());
            assertNotNull(session.getMapper(ConsultationMessageMapper.class).selectList(null).get(0).getRequestId());
            assertEquals("虚构分类", session.getMapper(CategoryMapper.class).selectById(301L).getCategoryName());
            assertEquals("虚构文章", session.getMapper(ArticleMapper.class).selectList(null).get(0).getTitle());
            assertEquals("平静", session.getMapper(EmotionDiaryMapper.class).selectList(null).get(0).getDominantEmotion());
        }
    }

    @Test
    void existingSchemaMigrationRepeatsAndPreservesUnlinkedHistoricalMessages() throws Exception {
        db.jdbc.execute("DROP TABLE ai_request_record");
        db.jdbc.execute("ALTER TABLE consultation_message DROP INDEX uk_message_request_sender, DROP COLUMN request_id");
        db.jdbc.execute("ALTER TABLE consultation_message MODIFY ai_model VARCHAR(50) NULL DEFAULT 'fixture-default' COMMENT 'fixture model column'");
        db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content) VALUES (201,1,'fictional historical row')");
        db.script("db/migrations/20261106_add_ai_request_record.sql");
        db.script("db/migrations/20261106_add_ai_request_record.sql");
        assertEquals(1, db.count("consultation_message"));
        assertNull(db.jdbc.queryForObject("SELECT request_id FROM consultation_message", String.class));
        assertEquals("fictional historical row", db.jdbc.queryForObject("SELECT content FROM consultation_message", String.class));
        assertEquals(100, db.jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='consultation_message' AND COLUMN_NAME='ai_model'", Integer.class));
        assertEquals("fixture-default", db.jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='consultation_message' AND COLUMN_NAME='ai_model'", String.class));
        assertEquals("fixture model column", db.jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='consultation_message' AND COLUMN_NAME='ai_model'", String.class));
        assertNotNull(db.create("after-upgrade").getUserMessageId());
    }

    @Test
    void concurrentSameClientIdCreatesExactlyOneRecordAndOneQuestion() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                outcomes.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { db.create("one-click-id"); return true; }
                    catch (DuplicateKeyException expected) { return false; }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> outcome : outcomes) { if (outcome.get(15, TimeUnit.SECONDS)) { admitted++; } }
            assertEquals(1, admitted);
            assertEquals(1, db.count("ai_request_record"));
            assertEquals(1, db.count("consultation_message"));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void userMessageInsertFailureRollsBackRequestAndAllowsExplicitNewAdmission() {
        db.jdbc.execute("CREATE TRIGGER batch3_fail_message BEFORE INSERT ON consultation_message FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected fixture failure'");
        assertThrows(DataAccessException.class, () -> db.create("user-write-failure"));
        assertEquals(0, db.count("ai_request_record"));
        assertEquals(0, db.count("consultation_message"));
        db.jdbc.execute("DROP TRIGGER batch3_fail_message");
        assertNotNull(db.create("user-write-failure").getUserMessageId());
    }

    @Test
    void finalStateWriteFailureRollsBackAiMessageAndKeepsDurableRunningState() {
        AiRequestRecord request = db.create("final-write-failure");
        db.requests.markDispatched(request);
        db.jdbc.execute("CREATE TRIGGER batch3_fail_final BEFORE UPDATE ON ai_request_record FOR EACH ROW BEGIN IF NEW.status <> 'RUNNING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected fixture final failure'; END IF; END");
        assertThrows(DataAccessException.class, () -> db.requests.finish(request, "SUCCEEDED", null, null, "fictional reply", true));
        assertEquals(1, db.count("consultation_message"));
        AiRequestRecord durable = db.requests.find(101L, "final-write-failure");
        assertEquals("RUNNING", durable.getStatus());
        assertNull(durable.getAiMessageId());
        db.jdbc.execute("DROP TRIGGER batch3_fail_final");
        db.newService().recoverUnfinished();
        assertEquals("UNKNOWN", db.requests.find(101L, "final-write-failure").getStatus());
    }

    @Test
    void concurrentFinishCallbacksCommitExactlyOneAssociatedReply() throws Exception {
        AiRequestRecord request = db.create("two-finishes");
        db.requests.markDispatched(request);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AiRequestRecord> first = workers.submit(() -> db.requests.finish(request, "SUCCEEDED", null, null, "虚构完成回复", true));
            Future<AiRequestRecord> second = workers.submit(() -> db.requests.finish(request, "SUCCEEDED", null, null, "duplicate callback", true));
            AiRequestRecord a = first.get(10, TimeUnit.SECONDS);
            AiRequestRecord b = second.get(10, TimeUnit.SECONDS);
            assertNotNull(a.getAiMessageId());
            assertEquals(a.getAiMessageId(), b.getAiMessageId());
            assertEquals(2, db.count("consultation_message"));
            assertEquals(24, Duration.between(a.getFinishedAt(), a.getExpiresAt()).toHours());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void uniqueMessageConstraintRejectsDuplicateSenderButAllowsHistoricalNullLinks() {
        AiRequestRecord request = db.create("message-unique");
        assertThrows(DuplicateKeyException.class, () -> db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content,request_id) VALUES (201,1,'duplicate',?)", request.getRequestId()));
        db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content) VALUES (201,1,'history one'),(201,1,'history two')");
        assertEquals(3, db.count("consultation_message"));
    }

    @Test
    void clientRequestIdsAreCaseSensitiveAndScopedToAuthenticatedUser() {
        AiRequestRecord a = db.create("CaseSensitiveID");
        AiRequestRecord b = db.create("casesensitiveid");
        AiRequestRecord other = db.requests.create(UUID.randomUUID().toString(), 102L, "CaseSensitiveID", 202L, "b".repeat(64), "fictional-model", "other fictional question");
        assertNotEquals(a.getRequestId(), b.getRequestId());
        assertEquals(other.getRequestId(), db.requests.find(102L, "CaseSensitiveID").getRequestId());
        assertEquals(a.getRequestId(), db.requests.find(101L, "CaseSensitiveID").getRequestId());
        ReplyFailure forbidden = assertThrows(ReplyFailure.class, () -> db.requests.create(UUID.randomUUID().toString(), 102L, "forbidden", 201L, "c".repeat(64), "fictional-model", "forbidden"));
        assertEquals("404", forbidden.getCode());
        assertEquals(3, db.count("ai_request_record"));
    }

    @Test
    void deletionPreventsNewRequestAndSuppressesReplySaveInAnExistingTask() {
        AiRequestRecord running = db.create("before-delete");
        db.requests.markDispatched(running);
        db.jdbc.update("UPDATE consultation_session SET deleted=true WHERE id=201");
        assertEquals("404", assertThrows(ReplyFailure.class, () -> db.create("after-delete")).getCode());
        AiRequestRecord terminal = db.requests.finish(running, "SUCCEEDED", null, null, "must not persist", true);
        assertEquals("FAILED", terminal.getStatus());
        assertEquals("410", terminal.getErrorCode());
        assertFalse(terminal.isSaved());
        assertEquals(1, db.count("consultation_message"));
    }

    @Test
    void terminalTtlRemovesMetadataOnlyAndNeverRunningEvenWithAnExpiredTimestamp() {
        AiRequestRecord expired = db.create("expired-result");
        AiRequestRecord running = db.create("still-running");
        db.requests.finish(expired, "SUCCEEDED", null, null, "kept conversation history", true);
        LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
        db.jdbc.update("UPDATE ai_request_record SET expires_at=? WHERE request_id IN (?,?)", old, expired.getRequestId(), running.getRequestId());
        db.requests.cleanupExpired();
        assertNull(db.requests.find(101L, "expired-result"));
        assertEquals("RUNNING", db.requests.find(101L, "still-running").getStatus());
        assertEquals(3, db.count("consultation_message"));
        assertNotNull(db.create("expired-result"));
    }

    @Test
    void restartRecoveryUsesCommittedAssociatedReplyAndLeavesMissingReplyUnknown() {
        AiRequestRecord saved = db.create("recovery-with-reply");
        AiRequestRecord unknown = db.create("recovery-no-reply");
        db.jdbc.update("INSERT INTO consultation_message (session_id,sender_type,content,request_id) VALUES (201,2,'committed fictional reply',?)", saved.getRequestId());
        db.newService().recoverUnfinished();
        AiRequestRecord recovered = db.requests.find(101L, "recovery-with-reply");
        assertEquals("SUCCEEDED", recovered.getStatus());
        assertTrue(recovered.isSaved());
        assertEquals("committed fictional reply", db.requests.replay(recovered));
        assertEquals("UNKNOWN", db.requests.find(101L, unknown.getClientRequestId()).getStatus());
        long messages = db.count("consultation_message");
        db.newService().recoverUnfinished();
        assertEquals(messages, db.count("consultation_message"));
    }
}
