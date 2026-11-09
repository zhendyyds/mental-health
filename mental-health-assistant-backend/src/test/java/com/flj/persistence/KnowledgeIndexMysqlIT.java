package com.flj.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.flj.DTO.command.ArticleCreateDTO;
import com.flj.DTO.command.ArticleUpdateDTO;
import com.flj.exception.BusinessException;
import com.flj.mapper.ArticleMapper;
import com.flj.mapper.CategoryMapper;
import com.flj.mapper.UserMapper;
import com.flj.rag.ClaimedJob;
import com.flj.rag.IndexCandidate;
import com.flj.rag.IndexChunk;
import com.flj.rag.IndexConfig;
import com.flj.rag.IndexJob;
import com.flj.rag.KnowledgeIndexRepository;
import com.flj.service.FileStorageService;
import com.flj.service.KnowledgeArticleService;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Opt-in tests against a guarded MySQL fixture created by verify-mysql.ps1. */
class KnowledgeIndexMysqlIT {
    private static final String LEGACY_ARTICLE = "00000000-0000-0000-0000-000000000997";
    private static final String MIGRATION = "db/migrations/20261107_add_knowledge_index.sql";
    private static final String ORIGINAL_TITLE = "Fictional article";
    private static final String ORIGINAL_BODY = "<p>Fictional original body.</p>";
    private static final String ORIGINAL_COVER = "/upload/fictional-old.png";
    private static final long CATEGORY_ID = 301L;
    private static final IndexConfig V1 = config("fixture-v1", "fixture_collection_v1", 3);
    private static final IndexConfig V2 = config("fixture-v2", "fixture_collection_v2", 4);

    private MysqlVerification db;
    private DataSourceTransactionManager transactions;
    private AnnotationConfigApplicationContext context;
    private KnowledgeIndexRepository index;
    private KnowledgeArticleService articles;
    private FileStorageService files;

    @BeforeEach
    void fixture() {
        db = new MysqlVerification();
        db.jdbc.execute("DROP TRIGGER IF EXISTS batch5_fail_queue");
        db.jdbc.execute("DROP TRIGGER IF EXISTS batch5_fail_finalize");
        db.jdbc.update("DELETE FROM knowledge_chunk");
        db.jdbc.update("DELETE FROM knowledge_index_job");
        db.jdbc.update("DELETE FROM knowledge_index_config");
        db.jdbc.update("DELETE FROM knowledge_article WHERE id <> ?", LEGACY_ARTICLE);
        db.jdbc.update("DELETE FROM knowledge_category");
        db.jdbc.update("INSERT INTO knowledge_category (id, category_name) VALUES (?, 'Fictional category')", CATEGORY_ID);

        transactions = new DataSourceTransactionManager(db.dataSource);
        index = new KnowledgeIndexRepository(db.jdbc, transactions, true, V1.version());
        index.registerConfig(V1);
        index.registerConfig(V2);
        files = mock(FileStorageService.class);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("knowledge-index-mysql", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(ArticleMapper.class);
        configuration.addMapper(CategoryMapper.class);
        configuration.addMapper(UserMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class, KnowledgeArticleService.class);
        context.registerBean(PlatformTransactionManager.class, () -> transactions);
        context.registerBean(KnowledgeIndexRepository.class, () -> index);
        context.registerBean(ArticleMapper.class, () -> session.getMapper(ArticleMapper.class));
        context.registerBean(CategoryMapper.class, () -> session.getMapper(CategoryMapper.class));
        context.registerBean(UserMapper.class, () -> session.getMapper(UserMapper.class));
        context.registerBean(FileStorageService.class, () -> files);
        context.refresh();
        articles = context.getBean(KnowledgeArticleService.class);
    }

    @AfterEach
    void closeFixture() {
        if (context != null) context.close();
    }

    @EnableTransactionManagement
    static class TransactionConfiguration { }

    @Test
    void migrationPreservesLegacyPublishedArticleAndRepeatsWithLiveIndexRows() throws Exception {
        assertEquals("Fictional legacy article", text("title", LEGACY_ARTICLE));
        assertEquals("<p>Fictional legacy source</p>", text("content", LEGACY_ARTICLE));
        assertEquals(1, status(LEGACY_ARTICLE));
        assertEquals(LocalDateTime.of(2026, 11, 7, 10, 0), publishedAt(LEGACY_ARTICLE));
        assertEquals(1L, revision(LEGACY_ARTICLE));
        assertFalse(ready(LEGACY_ARTICLE));
        assertNull(text("index_generation", LEGACY_ARTICLE));
        assertTrue(index.listJobs(LEGACY_ARTICLE, 10).isEmpty());

        String articleId = publish();
        IndexCandidate stored = finishNextBuild(articleId);
        db.script(MIGRATION);
        db.script(MIGRATION);
        assertEquals(stored.generation(), text("active_index_generation", articleId));
        assertTrue(ready(articleId));
        assertEquals(stored, index.validateCandidates(List.of(stored)).get(0).candidate());
        assertEquals(1L, db.count("knowledge_index_job"));
        assertEquals(1L, db.count("knowledge_chunk"));
        assertEquals("<p>Fictional legacy source</p>", text("content", LEGACY_ARTICLE));
    }

    @Test
    void realArticleEditsOnlyBumpRevisionForIndexedContentAndInvalidateImmediately() {
        String articleId = createDraft();
        assertEquals(1L, revision(articleId));
        assertTrue(index.listJobs(articleId, 10).isEmpty());
        articles.changeStatus(articleId, 1);
        IndexCandidate old = finishNextBuild(articleId);
        LocalDateTime firstPublished = publishedAt(articleId);

        articles.updateArticle(articleId, edit(ORIGINAL_TITLE, ORIGINAL_BODY, "/upload/fictional-new.png"));
        assertTrue(ready(articleId));
        assertEquals(1L, revision(articleId));
        assertEquals(old.generation(), text("active_index_generation", articleId));
        verify(files).deleteFile(ORIGINAL_COVER);

        articles.updateArticle(articleId, edit("Fictional revised title", "<p>Fictional revised body.</p>", "/upload/fictional-new.png"));
        assertEquals(2L, revision(articleId));
        assertEquals(firstPublished, publishedAt(articleId));
        assertFalse(ready(articleId));
        assertNotEquals(old.generation(), text("index_generation", articleId));
        assertTrue(index.validateCandidates(List.of(old)).isEmpty());
        assertEquals(1, status(articleId));
    }

    @Test
    void offlineRepublishAndDeleteNeverExposeThePreviousGeneration() {
        String articleId = publish();
        IndexCandidate previous = finishNextBuild(articleId);
        LocalDateTime firstPublished = publishedAt(articleId);
        articles.changeStatus(articleId, 2);
        assertFalse(ready(articleId));
        assertTrue(index.validateCandidates(List.of(previous)).isEmpty());

        articles.changeStatus(articleId, 1);
        assertEquals(firstPublished, publishedAt(articleId));
        assertEquals(previous.revision(), revision(articleId));
        assertNotEquals(previous.generation(), text("index_generation", articleId));
        deferCleanup(previous.generation());
        IndexCandidate current = finishNextBuild(articleId);
        assertEquals(List.of(current), index.validateCandidates(List.of(previous, current)).stream().map(c -> c.candidate()).toList());

        articles.softDeleteArticle(articleId);
        articles.softDeleteArticle(articleId);
        assertFalse(ready(articleId));
        assertTrue(index.validateCandidates(List.of(current)).isEmpty());
        assertEquals(1L, index.listJobs(articleId, 10).stream()
                .filter(job -> job.operation().equals("CLEANUP") && job.generation().equals(current.generation())).count());
    }

    @Test
    void sameRevisionConfigSwitchSupersedesOldLeaseAndReusesPendingNewGeneration() {
        String articleId = publish();
        ClaimedJob old = claim("old-worker", "BUILD");
        assertEquals(V1, old.config());
        assertEquals(V1, index.registerConfig(V1));
        assertEquals("409", assertThrows(BusinessException.class,
                () -> index.registerConfig(config(V1.version(), V1.collectionName(), 9))).getCode());
        assertEquals(V1, index.findConfig(V1.version()));

        KnowledgeIndexRepository switched = new KnowledgeIndexRepository(db.jdbc, transactions, true, V2.version());
        IndexJob next = switched.reindex(articleId, V2.version());
        IndexJob duplicate = switched.reindex(articleId, V2.version());
        assertEquals(next.id(), duplicate.id());
        assertEquals(next.generation(), duplicate.generation());
        assertEquals(old.job().revision(), next.revision());
        assertNotEquals(old.job().generation(), next.generation());
        assertEquals("SUPERSEDED", job(articleId, old.job().id()).status());
        assertFalse(index.renew(old, 30));
        assertFalse(index.finishBuild(old, List.of(chunk(old))));
        assertEquals("409", assertThrows(BusinessException.class, () -> switched.retry(old.job().id())).getCode());
        assertFalse(ready(articleId));

        deferCleanup(old.job().generation());
        index = switched;
        IndexCandidate current = finishNextBuild(articleId);
        assertEquals(V2.version(), current.configVersion());
        assertEquals(next.generation(), current.generation());
        assertTrue(ready(articleId));
    }

    @Test
    void concurrentClaimHasOneWinnerAndExpiredLeaseFencesItsOldToken() throws Exception {
        String articleId = publish();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<ClaimedJob> winners = new ArrayList<>();
        try {
            List<Future<ClaimedJob>> attempts = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String worker = "claim-worker-" + i;
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return index.claim(worker, 30);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<ClaimedJob> result : attempts) {
                ClaimedJob claimed = result.get(15, TimeUnit.SECONDS);
                if (claimed != null) winners.add(claimed);
            }
        } finally { workers.shutdownNow(); }
        assertEquals(1, winners.size());
        ClaimedJob old = winners.get(0);
        assertEquals(1, old.job().attemptCount());
        assertTrue(index.renew(old, 30));
        expireLease(old.job().id());
        assertFalse(index.renew(old, 30));

        // A new repository instance reads the persisted expiry and issues a new token.
        KnowledgeIndexRepository restarted = new KnowledgeIndexRepository(db.jdbc, transactions, true, V1.version());
        ClaimedJob replacement = Objects.requireNonNull(restarted.claim("replacement-worker", 30));
        assertEquals(old.job().id(), replacement.job().id());
        assertEquals(old.job().generation(), replacement.job().generation());
        assertEquals(2, replacement.job().attemptCount());
        assertNotEquals(old.job().leaseToken(), replacement.job().leaseToken());
        assertFalse(index.fail(old, "STALE_WORKER", true));
        assertFalse(index.finishBuild(old, List.of(chunk(old))));
        assertEquals(chunk(old), chunk(replacement));
        assertTrue(restarted.finishBuild(replacement, List.of(chunk(replacement))));
        assertEquals(1L, db.count("knowledge_chunk"));
        assertTrue(ready(articleId));
    }

    @Test
    void failedEnqueueRollsBackActualArticlePublish() {
        String articleId = createDraft();
        db.jdbc.execute("CREATE TRIGGER batch5_fail_queue BEFORE INSERT ON knowledge_index_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fictional enqueue failure'");
        assertThrows(DataAccessException.class, () -> articles.changeStatus(articleId, 1));
        assertEquals(0, status(articleId));
        assertNull(publishedAt(articleId));
        assertNull(text("index_generation", articleId));
        assertTrue(index.listJobs(articleId, 10).isEmpty());
    }

    @Test
    void exhaustedLeasesStopAutomaticClaimsAndAnExplicitRetryKeepsGeneration() {
        String articleId = publish();
        ClaimedJob first = claim("attempt-one", "BUILD");
        expireLease(first.job().id());
        ClaimedJob second = claim("attempt-two", "BUILD");
        expireLease(second.job().id());
        ClaimedJob third = claim("attempt-three", "BUILD");
        expireLease(third.job().id());
        assertNull(index.claim("no-fourth-attempt", 30));
        IndexJob failed = job(articleId, first.job().id());
        assertEquals("FAILED", failed.status());
        assertEquals("LEASE_ATTEMPTS_EXHAUSTED", failed.errorCode());
        assertTrue(failed.retryable());
        assertTrue(index.listJobs(articleId, 10).stream().noneMatch(job -> job.operation().equals("CLEANUP")));

        IndexJob queued = index.retry(failed.id());
        assertEquals(failed.generation(), queued.generation());
        assertEquals(0, queued.attemptCount());
        ClaimedJob manual = claim("manual-retry", "BUILD");
        assertNotEquals(third.job().leaseToken(), manual.job().leaseToken());
        assertTrue(index.finishBuild(manual, List.of(chunk(manual))));
        assertTrue(ready(articleId));
    }

    @Test
    void articleEditRollbackKeepsOldIndexAndDefersCoverDeletionUntilCommit() {
        String articleId = publish();
        IndexCandidate old = finishNextBuild(articleId);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThrows(FixtureRollback.class, () -> transaction.executeWithoutResult(tx -> {
            articles.updateArticle(articleId, edit("Fictional uncommitted edit", "<p>New uncommitted body.</p>", "/upload/fictional-new.png"));
            assertFalse(ready(articleId));
            assertEquals(2L, revision(articleId));
            verifyNoInteractions(files);
            throw new FixtureRollback();
        }));
        assertEquals(ORIGINAL_TITLE, text("title", articleId));
        assertEquals(ORIGINAL_COVER, text("cover_image", articleId));
        assertEquals(1L, revision(articleId));
        assertTrue(ready(articleId));
        assertEquals(old.generation(), text("active_index_generation", articleId));
        assertEquals(1, index.listJobs(articleId, 10).size());
        verifyNoInteractions(files);
    }

    @Test
    void failedBuildFinalizationRollsBackChunksAndAllowsSameTokenToFinish() {
        String articleId = publish();
        ClaimedJob running = claim("build-worker", "BUILD");
        IndexChunk chunk = chunk(running);
        db.jdbc.execute("CREATE TRIGGER batch5_fail_finalize BEFORE UPDATE ON knowledge_article FOR EACH ROW BEGIN IF NEW.index_ready THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fictional finalization failure'; END IF; END");
        assertThrows(DataAccessException.class, () -> index.finishBuild(running, List.of(chunk)));
        assertEquals(0L, db.count("knowledge_chunk"));
        assertEquals("RUNNING", job(articleId, running.job().id()).status());
        assertFalse(ready(articleId));
        assertNull(text("active_index_generation", articleId));
        db.jdbc.execute("DROP TRIGGER batch5_fail_finalize");
        assertTrue(index.finishBuild(running, List.of(chunk)));
        assertFalse(index.finishBuild(running, List.of(chunk)));
        assertEquals(1L, db.count("knowledge_chunk"));
        assertTrue(ready(articleId));
    }

    @Test
    void cleanupRetriesDeleteOnlyTheirOldGenerationAndPreserveNewEvidence() {
        String articleId = publish();
        ClaimedJob oldBuild = claim("old-build-worker", "BUILD");
        IndexChunk oldChunk = chunk(oldBuild);
        assertTrue(index.finishBuild(oldBuild, List.of(oldChunk)));
        IndexCandidate old = candidate(oldBuild, oldChunk);
        index.reindex(articleId, V1.version());
        deferCleanup(old.generation());
        IndexCandidate current = finishNextBuild(articleId);
        assertNotEquals(old.generation(), current.generation());
        IndexJob cleanup = index.listJobs(articleId, 10).stream()
                .filter(job -> job.operation().equals("CLEANUP") && job.generation().equals(old.generation()))
                .findFirst().orElseThrow();
        assertFalse(cleanup.nextRetryAt().isBefore(oldBuild.job().leaseUntil().plusSeconds(30)));
        makeDue(cleanup.id());
        ClaimedJob first = claim("cleanup-worker", "CLEANUP");
        assertEquals(cleanup.id(), first.job().id());
        assertTrue(index.fail(first, "FICTIONAL_DELETE_TIMEOUT", true));
        assertEquals("RETRY_WAIT", job(articleId, cleanup.id()).status());
        assertEquals(2L, db.count("knowledge_chunk"));

        makeDue(cleanup.id());
        ClaimedJob retry = claim("cleanup-retry-worker", "CLEANUP");
        assertEquals(old.generation(), retry.job().generation());
        assertNotEquals(first.job().leaseToken(), retry.job().leaseToken());
        assertFalse(index.finishCleanup(first));
        assertTrue(index.finishCleanup(retry));
        assertEquals(1L, db.count("knowledge_chunk"));
        assertEquals(current.generation(), text("active_index_generation", articleId));
        assertEquals(List.of(current), index.validateCandidates(List.of(old, current)).stream().map(c -> c.candidate()).toList());

        index.requeueAbandonedCleanup(oldBuild);
        assertEquals("PENDING", job(articleId, cleanup.id()).status());
        makeDue(cleanup.id());
        assertTrue(index.finishCleanup(claim("late-write-cleanup", "CLEANUP")));
        assertEquals(List.of(current), index.validateCandidates(List.of(old, current)).stream().map(c -> c.candidate()).toList());
    }

    private static IndexConfig config(String version, String collection, int dimensions) {
        return new IndexConfig(version, "fictional", "https://embedding.example.invalid", "fictional-model",
                dimensions, collection, "jsoup-v1", "codepoint-window-v1", 256, 32);
    }

    private String createDraft() {
        String id = UUID.randomUUID().toString();
        ArticleCreateDTO dto = new ArticleCreateDTO();
        dto.setId(id);
        dto.setCategoryId(CATEGORY_ID);
        dto.setTitle(ORIGINAL_TITLE);
        dto.setContent(ORIGINAL_BODY);
        dto.setCoverImage(ORIGINAL_COVER);
        articles.createArticle(101L, dto);
        return id;
    }

    private String publish() {
        String id = createDraft();
        articles.changeStatus(id, 1);
        return id;
    }

    private static ArticleUpdateDTO edit(String title, String content, String cover) {
        ArticleUpdateDTO dto = new ArticleUpdateDTO();
        dto.setCategoryId(CATEGORY_ID);
        dto.setTitle(title);
        dto.setContent(content);
        dto.setCoverImage(cover);
        dto.setTags("fictional,verification");
        return dto;
    }

    private ClaimedJob claim(String owner, String expectedOperation) {
        ClaimedJob claimed = index.claim(owner, 30);
        assertNotNull(claimed);
        assertEquals(expectedOperation, claimed.job().operation());
        return claimed;
    }

    private IndexCandidate finishNextBuild(String articleId) {
        ClaimedJob claimed = claim("build-worker", "BUILD");
        assertEquals(articleId, claimed.job().articleId());
        IndexChunk chunk = chunk(claimed);
        assertTrue(index.finishBuild(claimed, List.of(chunk)));
        return candidate(claimed, chunk);
    }

    private static IndexCandidate candidate(ClaimedJob claimed, IndexChunk chunk) {
        return new IndexCandidate(claimed.job().articleId(), claimed.job().revision(), claimed.job().configVersion(),
                claimed.job().generation(), chunk.chunkId(), chunk.vectorId());
    }

    private static IndexChunk chunk(ClaimedJob claimed) {
        return IndexChunk.create(claimed.job(), 0, "Fictional section", "Fictional cited source.");
    }

    private IndexJob job(String articleId, long id) {
        return index.listJobs(articleId, 20).stream().filter(job -> job.id() == id).findFirst().orElseThrow();
    }

    private void expireLease(long jobId) {
        db.jdbc.update("UPDATE knowledge_index_job SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", jobId);
    }

    private void makeDue(long jobId) {
        db.jdbc.update("UPDATE knowledge_index_job SET next_retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", jobId);
    }

    private void deferCleanup(String generation) {
        db.jdbc.update("UPDATE knowledge_index_job SET next_retry_at = UTC_TIMESTAMP(6) + INTERVAL 1 HOUR WHERE index_generation = ? AND operation = 'CLEANUP'", generation);
    }

    private int status(String articleId) {
        return db.jdbc.queryForObject("SELECT status FROM knowledge_article WHERE id = ?", Integer.class, articleId);
    }

    private long revision(String articleId) {
        return db.jdbc.queryForObject("SELECT content_revision FROM knowledge_article WHERE id = ?", Long.class, articleId);
    }

    private boolean ready(String articleId) {
        return Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT index_ready FROM knowledge_article WHERE id = ?", Boolean.class, articleId));
    }

    private LocalDateTime publishedAt(String articleId) {
        return db.jdbc.queryForObject("SELECT published_at FROM knowledge_article WHERE id = ?",
                (row, number) -> row.getObject(1, LocalDateTime.class), articleId);
    }

    private String text(String column, String articleId) {
        return db.jdbc.queryForObject("SELECT " + column + " FROM knowledge_article WHERE id = ?", String.class, articleId);
    }

    private static final class FixtureRollback extends RuntimeException { }
}
