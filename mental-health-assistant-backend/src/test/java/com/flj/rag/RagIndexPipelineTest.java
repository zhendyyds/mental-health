package com.flj.rag;

import com.flj.AiService.governance.AiCallGovernor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagIndexPipelineTest {
    private static final String ARTICLE = "00000000-0000-0000-0000-000000000001";
    private static final String GENERATION = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_GENERATION = "00000000-0000-0000-0000-000000000003";
    private static final float[] VECTOR = {1, 0, 0};

    @Test
    void chunkerPreservesSectionsAndParagraphsWithStableOverlapAndIds() {
        IndexConfig config = settings("v1").currentConfig();
        String paragraph = "甲".repeat(420) + "。";
        String following = "后续内容" + "乙".repeat(290);
        String html = "<h2>第一节</h2><p onclick=\"unsafe()\">" + paragraph + "</p>"
                + "<script>hiddenScript()</script><p>" + following + "</p><h2>第二节</h2>"
                + "<p>可见<strong>内容</strong><img src=\"https://fixture.invalid/ignored.png\"></p>";
        ClaimedJob job = job(config, "BUILD", GENERATION, "标题", html);
        RagChunker chunker = new RagChunker();

        List<IndexChunk> chunks = chunker.chunk(job);

        assertEquals(3, chunks.size());
        assertEquals("第一节", chunks.get(0).section());
        assertEquals(paragraph, chunks.get(0).content());
        assertEquals(paragraph.substring(paragraph.length() - 100) + "\n\n" + following, chunks.get(1).content());
        assertEquals("第二节", chunks.get(2).section());
        assertEquals("可见内容", chunks.get(2).content());
        assertEquals(chunks, chunker.chunk(job));
        List<IndexChunk> rebuilt = chunker.chunk(job(config, "BUILD", OTHER_GENERATION, "标题", html));
        assertEquals(chunks.get(0).contentHash(), rebuilt.get(0).contentHash());
        assertNotEquals(chunks.get(0).vectorId(), rebuilt.get(0).vectorId());
    }

    @Test
    void chunkerDoesNotSplitSupplementaryCharactersAndRejectsImageOnlyContent() {
        IndexConfig config = settings("v1").currentConfig();
        RagChunker chunker = new RagChunker();
        List<IndexChunk> chunks = chunker.chunk(job(config, "BUILD", GENERATION, "标题", "<p>" + "🦊".repeat(1550) + "</p>"));

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().codePoints().allMatch(point -> point == 0x1F98A)
                && chunk.content().codePointCount(0, chunk.content().length()) <= config.chunkSize()));
        RagIndexException error = assertThrows(RagIndexException.class, () -> chunker.chunk(
                job(config, "BUILD", GENERATION, "标题", "<img src=\"https://fixture.invalid/image.png\">")));
        assertEquals("RAG_ARTICLE_NO_TEXT", error.getErrorCode());
        assertFalse(error.isRetryable());
    }

    @Test
    void workerSplitsActualUtf8BudgetAndStoresOnlyBodyForCitations() {
        RagIndexSettings settings = settings("v1");
        settings.setChunkSize(800);
        String title = "📚".repeat(200);
        String heading = "📝".repeat(255);
        ClaimedJob claimed = job(settings.currentConfig(), "BUILD", GENERATION, title,
                "<h2>" + heading + "</h2><p>" + "🦊".repeat(6500) + "</p>");
        KnowledgeIndexRepository repository = liveRepository(claimed, settings);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        RagVectorGateway vectors = mock(RagVectorGateway.class);
        List<Integer> batchSizes = new ArrayList<>();
        when(embeddings.embed(eq(claimed.config()), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(texts.stream().allMatch(text -> text.startsWith(title + "\n" + heading + "\n")));
            long bytes = texts.stream().mapToLong(AiCallGovernor::utf8Bytes).sum();
            assertTrue(bytes + RagIndexSettings.EMBEDDING_PROTOCOL_RESERVE <= settings.getEmbeddingInputUnits());
            batchSizes.add(texts.size());
            return texts.stream().map(text -> VECTOR.clone()).toList();
        });
        when(repository.finishBuild(eq(claimed), anyList())).thenReturn(true);
        RagIndexWorker worker = worker(repository, settings, embeddings, vectors);

        assertTrue(worker.runOnce());

        assertEquals(List.of(6, 4), batchSizes);
        verify(vectors, times(2)).upsert(eq(claimed.config()), anyList());
        verify(repository).finishBuild(eq(claimed), argThat(chunks -> chunks.size() == 10
                && chunks.stream().allMatch(chunk -> !chunk.content().contains(title) && heading.equals(chunk.section()))));
        verify(repository, never()).requeueAbandonedCleanup(any());
    }

    @Test
    void supersededWhileEmbeddingDoesNotWriteVectors() {
        RagIndexSettings settings = settings("v1");
        ClaimedJob claimed = job(settings.currentConfig(), "BUILD", GENERATION, "标题", "<p>正文</p>");
        KnowledgeIndexRepository repository = liveRepository(claimed, settings);
        when(repository.renew(claimed, settings.getLeaseSeconds())).thenReturn(true, false);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        when(embeddings.embed(eq(claimed.config()), anyList())).thenReturn(List.of(VECTOR));
        RagVectorGateway vectors = mock(RagVectorGateway.class);

        assertTrue(worker(repository, settings, embeddings, vectors).runOnce());

        verifyNoInteractions(vectors);
        verify(repository, never()).finishBuild(any(), anyList());
        verify(repository, never()).requeueAbandonedCleanup(any());
    }

    @Test
    void ambiguousLateWriteRequeuesAbandonedGenerationEvenAfterTemporaryDbFailure() {
        RagIndexSettings settings = settings("v1");
        ClaimedJob claimed = job(settings.currentConfig(), "BUILD", GENERATION, "标题", "<p>正文</p>");
        KnowledgeIndexRepository repository = liveRepository(claimed, settings);
        when(repository.claim(anyString(), eq(settings.getLeaseSeconds()))).thenReturn(claimed, null);
        doThrow(new IllegalStateException("temporary database failure")).doNothing()
                .when(repository).requeueAbandonedCleanup(claimed);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        when(embeddings.embed(eq(claimed.config()), anyList())).thenReturn(List.of(VECTOR));
        RagVectorGateway vectors = mock(RagVectorGateway.class);
        doThrow(new RagIndexException("504", "RAG_QDRANT_TIMEOUT", "写入确认超时", true))
                .when(vectors).upsert(eq(claimed.config()), anyList());
        RagIndexWorker worker = worker(repository, settings, embeddings, vectors);

        assertThrows(IllegalStateException.class, worker::runOnce);
        assertFalse(worker.runOnce());

        verify(repository).fail(claimed, "RAG_QDRANT_TIMEOUT", true);
        verify(repository, times(2)).requeueAbandonedCleanup(claimed);
        verify(vectors).upsert(eq(claimed.config()), anyList());
        verify(repository, never()).finishBuild(any(), anyList());
    }

    @Test
    void cleanupUsesTheOldCollectionAndExactGenerationWithoutEmbedding() {
        RagIndexSettings settings = settings("v2");
        ClaimedJob old = job(settings("v1").currentConfig(), "CLEANUP", GENERATION, null, null);
        KnowledgeIndexRepository repository = liveRepository(old, settings);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        RagVectorGateway vectors = mock(RagVectorGateway.class);

        assertTrue(worker(repository, settings, embeddings, vectors).runOnce());

        verify(vectors).deleteGeneration(old.config(), ARTICLE, 1, GENERATION);
        verify(repository).finishCleanup(old);
        verifyNoInteractions(embeddings);
    }

    @Test
    void disabledWorkerAndRetrievalDoNotTouchDbOrNetwork() {
        RagIndexSettings settings = new RagIndexSettings();
        KnowledgeIndexRepository repository = mock(KnowledgeIndexRepository.class);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        RagVectorGateway vectors = mock(RagVectorGateway.class);

        assertFalse(worker(repository, settings, embeddings, vectors).runOnce());
        var retrieval = new RagRetrievalService(repository, settings, embeddings, vectors);
        assertEquals("503", assertThrows(RagIndexException.class, () -> retrieval.retrieve(1L, "问题")).getCode());
        verifyNoInteractions(repository, embeddings, vectors);
    }

    @Test
    void retrievalUsesDbTextAndRechecksIdentityAndContentHashBeforeReturningCitations() {
        RagIndexSettings settings = settings("v1");
        IndexConfig config = settings.currentConfig();
        IndexCandidate valid = candidate("v1", GENERATION);
        IndexCandidate stale = candidate("v1", OTHER_GENERATION);
        IndexCandidate wrongConfig = candidate("v2", GENERATION);
        RagEmbeddingGateway embeddings = mock(RagEmbeddingGateway.class);
        when(embeddings.embed(config, List.of("问题"))).thenReturn(List.of(VECTOR));
        RagVectorGateway vectors = mock(RagVectorGateway.class);
        when(vectors.search(config, VECTOR, settings.getTopK(), settings.getScoreThreshold())).thenReturn(List.of(
                new RagVectorMatch(wrongConfig, 0.99), new RagVectorMatch(stale, 0.95),
                new RagVectorMatch(valid, 0.9), new RagVectorMatch(valid, 0.8)));
        KnowledgeIndexRepository repository = mock(KnowledgeIndexRepository.class);
        ValidatedChunk row = new ValidatedChunk(valid, "数据库标题", "章节", "数据库原文", "original-hash");
        when(repository.validateCandidates(List.of(stale, valid))).thenReturn(List.of(row));
        when(repository.validateCandidates(List.of(valid))).thenReturn(List.of(row),
                List.of(new ValidatedChunk(valid, "数据库标题", "章节", "修改后原文", "changed-hash")), List.of());
        var retrieval = new RagRetrievalService(repository, settings, embeddings, vectors);

        List<RagEvidence> evidence = retrieval.retrieve(1L, " 问题 ");

        assertEquals(List.of(new RagEvidence(valid, "数据库标题", "章节", "数据库原文", "original-hash", 0.9)), evidence);
        assertEquals(evidence, retrieval.revalidate(evidence));
        assertTrue(retrieval.revalidate(evidence).isEmpty());
        assertTrue(retrieval.revalidate(evidence).isEmpty());
        verify(embeddings).embed(config, List.of("问题"));
        verify(vectors).search(config, VECTOR, settings.getTopK(), settings.getScoreThreshold());
    }

    @Test
    void embeddingBudgetRefundsRejectedInputAndUsesProviderIndexesAndExplicitDimensions() {
        RagIndexSettings settings = settings("v1");
        settings.setEmbeddingInputUnits(1024);
        settings.setEmbeddingDailyCalls(1);
        IndexConfig config = settings.currentConfig();
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(new float[]{0, 1, 0}, 1), new Embedding(VECTOR, 0))));
        RagEmbeddingGateway gateway = new SpringAiRagEmbeddingGateway(settings, model);

        assertEquals("400", assertThrows(RagIndexException.class,
                () -> gateway.embed(config, List.of("汉".repeat(200)))).getCode());
        verifyNoInteractions(model);
        List<float[]> result = gateway.embed(config, List.of("第一段", "第二段"));
        assertArrayEquals(VECTOR, result.get(0));
        assertArrayEquals(new float[]{0, 1, 0}, result.get(1));
        assertEquals("429", assertThrows(RagIndexException.class,
                () -> gateway.embed(config, List.of("配额已用完"))).getCode());

        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).call(request.capture());
        OpenAiEmbeddingOptions options = assertInstanceOf(OpenAiEmbeddingOptions.class, request.getValue().getOptions());
        assertEquals(config.dimension(), options.getDimensions());
        assertEquals(config.embeddingModel(), options.getModel());
        assertEquals("float", options.getEncodingFormat());
    }

    @Test
    void embeddingRejectsWrongEndpointAndMalformedVectorResponses() {
        RagIndexSettings settings = settings("v1");
        EmbeddingModel model = mock(EmbeddingModel.class);
        var gateway = new SpringAiRagEmbeddingGateway(settings, model);
        RagIndexSettings foreign = settings("v2");
        foreign.setEmbeddingBaseUrl("https://different.fixture.invalid/v1");

        assertEquals("RAG_EMBEDDING_CONFIG_MISMATCH", assertThrows(RagIndexException.class,
                () -> gateway.embed(foreign.currentConfig(), List.of("问题"))).getErrorCode());
        verifyNoInteractions(model);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(VECTOR, 0), new Embedding(new float[]{Float.NaN, 1, 0}, 0))));
        assertEquals("502", assertThrows(RagIndexException.class,
                () -> gateway.embed(settings.currentConfig(), List.of("问题一", "问题二"))).getCode());
    }

    private static RagIndexSettings settings(String version) {
        var settings = new RagIndexSettings();
        settings.setEnabled(true);
        settings.setVersion(version);
        settings.setCollectionName("rag_fixture_" + version);
        settings.setEmbeddingProvider("openai-compatible");
        settings.setEmbeddingBaseUrl("https://embedding.fixture.invalid/v1");
        settings.setDimension(3);
        return settings;
    }

    private static ClaimedJob job(IndexConfig config, String operation, String generation, String title, String body) {
        IndexJob job = new IndexJob(1, ARTICLE, 1, config.version(), generation, operation, "RUNNING", 1, true,
                null, "worker", UUID.randomUUID().toString(), null, null, null, null, null);
        return new ClaimedJob(job, config, title, body);
    }

    private static IndexCandidate candidate(String version, String generation) {
        String id = UUID.randomUUID().toString();
        return new IndexCandidate(ARTICLE, 1, version, generation, id, id);
    }

    private static KnowledgeIndexRepository liveRepository(ClaimedJob claimed, RagIndexSettings settings) {
        KnowledgeIndexRepository repository = mock(KnowledgeIndexRepository.class);
        when(repository.claim(anyString(), eq(settings.getLeaseSeconds()))).thenReturn(claimed);
        when(repository.renew(claimed, settings.getLeaseSeconds())).thenReturn(true);
        return repository;
    }

    private static RagIndexWorker worker(KnowledgeIndexRepository repository, RagIndexSettings settings,
                                         RagEmbeddingGateway embeddings, RagVectorGateway vectors) {
        return new RagIndexWorker(repository, settings, new RagChunker(), embeddings, vectors);
    }
}
