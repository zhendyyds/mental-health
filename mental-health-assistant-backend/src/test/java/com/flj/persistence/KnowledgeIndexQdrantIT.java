package com.flj.persistence;

import com.flj.rag.IndexConfig;
import com.flj.rag.IndexJob;
import com.flj.rag.KnowledgeIndexRepository;
import com.flj.rag.QdrantRagVectorGateway;
import com.flj.rag.RagChunker;
import com.flj.rag.RagEmbeddingGateway;
import com.flj.rag.RagEvidence;
import com.flj.rag.RagIndexException;
import com.flj.rag.RagIndexSettings;
import com.flj.rag.RagIndexWorker;
import com.flj.rag.RagRetrievalService;
import com.flj.rag.RagVectorGateway;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Opt-in tests for production index and retrieval components with two real local stores. */
@Timeout(75)
class KnowledgeIndexQdrantIT {
    private MysqlVerification db;
    private String articleId;
    private int port;

    @BeforeEach
    void prepareIsolatedStores() {
        db = new MysqlVerification();
        port = Integer.parseInt(System.getenv().getOrDefault("BATCH5_QDRANT_PORT", "0"));
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Run the IndexSuite with its private Qdrant server");
        }
        db.jdbc.update("DELETE FROM knowledge_chunk");
        db.jdbc.update("DELETE FROM knowledge_index_job");
        db.jdbc.update("DELETE FROM knowledge_index_config");
        db.jdbc.update("DELETE FROM knowledge_article");
        articleId = UUID.randomUUID().toString();
        db.jdbc.update("INSERT INTO knowledge_article (id,title,content,status) VALUES (?,?,?,1)",
                articleId, "虚构学习手册", "<h2>练习</h2><p>先阅读一段说明，再完成一次练习。</p>");
    }

    @Test
    void uncertainVectorWriteRetriesSameGenerationAndCleanupPreservesNewerPoints() throws Exception {
        RagIndexSettings settings = settings("fixture-v1", 3);
        IndexConfig config = settings.currentConfig();
        KnowledgeIndexRepository repository = repository(config);
        try (QdrantClient server = openClient(); QdrantRagVectorGateway vectors = new QdrantRagVectorGateway(settings)) {
            createCollection(server, config);
            RagVectorGateway lostAcknowledgement = mock(RagVectorGateway.class, delegatesTo(vectors));
            AtomicBoolean firstWrite = new AtomicBoolean(true);
            doAnswer(call -> {
                vectors.upsert(call.getArgument(0), call.getArgument(1));
                if (firstWrite.getAndSet(false)) {
                    throw new RagIndexException("504", "FIXTURE_ACK_LOST", "虚构的写入确认超时", true);
                }
                return null;
            }).when(lostAcknowledgement).upsert(any(IndexConfig.class), anyList());
            RagIndexWorker worker = worker(repository, settings, lostAcknowledgement);
            RagRetrievalService retrieval = new RagRetrievalService(repository, settings, embeddings(), vectors);

            IndexJob original = repository.reindex(articleId, config.version());
            assertTrue(worker.runOnce());
            assertEquals("RETRY_WAIT", job(repository, original.id()).status());
            long points = count(server, config);
            assertTrue(points > 0);
            assertTrue(retrieval.retrieve(1L, "练习步骤是什么？").isEmpty());
            var unacknowledgedIds = vectors.search(config, axis(config), 10, 0).stream().map(hit -> hit.candidate().vectorId()).toList();

            makeDue(original.id());
            assertTrue(worker.runOnce());
            assertEquals("SUCCEEDED", job(repository, original.id()).status());
            assertEquals(2, job(repository, original.id()).attemptCount());
            assertEquals(points, count(server, config));
            List<RagEvidence> oldEvidence = retrieval.retrieve(1L, "练习步骤是什么？");
            assertFalse(oldEvidence.isEmpty());
            assertEquals(unacknowledgedIds, oldEvidence.stream().map(evidence -> evidence.candidate().vectorId()).toList());
            assertTrue(oldEvidence.get(0).content().contains("再完成一次练习"));

            IndexJob replacement = repository.reindex(articleId, config.version());
            assertNotEquals(original.generation(), replacement.generation());
            assertTrue(retrieval.revalidate(oldEvidence).isEmpty());
            assertTrue(retrieval.retrieve(1L, "练习步骤是什么？").isEmpty());
            assertEquals(points, count(server, config));
            assertTrue(worker.runOnce());
            assertEquals("SUCCEEDED", job(repository, replacement.id()).status());
            assertEquals(points * 2, count(server, config));

            IndexJob cleanup = repository.listJobs(articleId, 30).stream()
                    .filter(candidate -> "CLEANUP".equals(candidate.operation()) && original.generation().equals(candidate.generation()))
                    .findFirst().orElseThrow();
            makeDue(cleanup.id());
            assertTrue(worker.runOnce());
            assertEquals("SUCCEEDED", job(repository, cleanup.id()).status());
            assertEquals(points, count(server, config));
            var surviving = vectors.search(config, axis(config), 10, 0);
            assertFalse(surviving.isEmpty());
            assertTrue(surviving.stream().allMatch(hit -> replacement.generation().equals(hit.candidate().generation())));
            assertFalse(retrieval.retrieve(1L, "练习步骤是什么？").isEmpty());
        }
    }

    @Test
    void versionSwitchUsesSeparateDimensionAndOfflineFilterRejectsStillStoredVectors() throws Exception {
        RagIndexSettings first = settings("fixture-v1", 3);
        RagIndexSettings second = settings("fixture-v2", 4);
        IndexConfig firstConfig = first.currentConfig();
        IndexConfig secondConfig = second.currentConfig();
        KnowledgeIndexRepository firstRepository = repository(firstConfig);
        KnowledgeIndexRepository secondRepository = repository(secondConfig);
        try (QdrantClient server = openClient(); QdrantRagVectorGateway vectors = new QdrantRagVectorGateway(first)) {
            createCollection(server, firstConfig);
            createCollection(server, secondConfig);
            firstRepository.reindex(articleId, firstConfig.version());
            assertTrue(worker(firstRepository, first, vectors).runOnce());
            RagRetrievalService firstRetrieval = new RagRetrievalService(firstRepository, first, embeddings(), vectors);
            List<RagEvidence> firstEvidence = firstRetrieval.retrieve(1L, "练习步骤是什么？");
            assertFalse(firstEvidence.isEmpty());

            secondRepository.reindex(articleId, secondConfig.version());
            RagRetrievalService secondRetrieval = new RagRetrievalService(secondRepository, second, embeddings(), vectors);
            assertTrue(firstRetrieval.revalidate(firstEvidence).isEmpty());
            assertTrue(secondRetrieval.retrieve(1L, "练习步骤是什么？").isEmpty());
            assertTrue(worker(secondRepository, second, vectors).runOnce());
            List<RagEvidence> current = secondRetrieval.retrieve(1L, "练习步骤是什么？");
            assertFalse(current.isEmpty());
            assertTrue(current.stream().allMatch(hit -> secondConfig.version().equals(hit.candidate().configVersion())));

            db.jdbc.update("UPDATE knowledge_article SET status=2 WHERE id=?", articleId);
            secondRepository.invalidate(articleId);
            assertTrue(count(server, secondConfig) > 0);
            assertTrue(secondRetrieval.retrieve(1L, "练习步骤是什么？").isEmpty());
            assertTrue(secondRetrieval.revalidate(current).isEmpty());
        }
    }

    private RagIndexSettings settings(String version, int dimension) {
        RagIndexSettings settings = new RagIndexSettings();
        settings.setEnabled(true);
        settings.setVersion(version);
        settings.setCollectionName("idx_" + UUID.randomUUID().toString().substring(0, 8));
        settings.setEmbeddingProvider("openai-compatible");
        settings.setEmbeddingBaseUrl("https://fixture.example.invalid/v1");
        settings.setEmbeddingModel(version);
        settings.setDimension(dimension);
        settings.setQdrantPort(port);
        settings.setScoreThreshold(0);
        return settings;
    }

    private KnowledgeIndexRepository repository(IndexConfig config) {
        KnowledgeIndexRepository repository = new KnowledgeIndexRepository(db.jdbc,
                new DataSourceTransactionManager(db.dataSource), true, config.version());
        repository.registerConfig(config);
        return repository;
    }

    private RagIndexWorker worker(KnowledgeIndexRepository repository, RagIndexSettings settings, RagVectorGateway vectors) {
        return new RagIndexWorker(repository, settings, new RagChunker(), embeddings(), vectors);
    }

    private static RagEmbeddingGateway embeddings() {
        return (config, texts) -> texts.stream().map(text -> axis(config)).toList();
    }

    private static float[] axis(IndexConfig config) {
        float[] vector = new float[config.dimension()];
        vector[0] = 1;
        return vector;
    }

    private QdrantClient openClient() {
        return new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", port, false)
                .withTimeout(Duration.ofSeconds(5)).build());
    }

    private static void createCollection(QdrantClient server, IndexConfig config) throws Exception {
        assertEquals("1.16.3", server.healthCheckAsync().get(5, TimeUnit.SECONDS).getVersion());
        assertTrue(server.createCollectionAsync(config.collectionName(), VectorParams.newBuilder()
                .setDistance(Distance.Cosine).setSize(config.dimension()).build()).get(5, TimeUnit.SECONDS).getResult());
    }

    private static long count(QdrantClient server, IndexConfig config) throws Exception {
        return server.getCollectionInfoAsync(config.collectionName()).get(5, TimeUnit.SECONDS).getPointsCount();
    }

    private IndexJob job(KnowledgeIndexRepository repository, long id) {
        return repository.listJobs(articleId, 30).stream().filter(candidate -> candidate.id() == id).findFirst().orElseThrow();
    }

    private void makeDue(long id) {
        assertEquals(1, db.jdbc.update("UPDATE knowledge_index_job SET next_retry_at=UTC_TIMESTAMP(6) WHERE id=?", id));
    }
}
