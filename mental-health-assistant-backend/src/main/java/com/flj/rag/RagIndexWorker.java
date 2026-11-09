package com.flj.rag;

import com.flj.AiService.governance.AiCallGovernor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RagIndexWorker {
    private static final Logger log = LoggerFactory.getLogger(RagIndexWorker.class);
    private final String owner = "rag-" + UUID.randomUUID();
    private final KnowledgeIndexRepository repository;
    private final RagIndexSettings settings;
    private final RagChunker chunker;
    private final RagEmbeddingGateway embeddings;
    private final RagVectorGateway vectors;
    private ClaimedJob pendingCleanup;

    public RagIndexWorker(KnowledgeIndexRepository repository, RagIndexSettings settings, RagChunker chunker,
                          RagEmbeddingGateway embeddings, RagVectorGateway vectors) {
        this.repository = repository;
        this.settings = settings;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    /** Claims at most one task. Network operations never run in the repository's transactions. */
    public synchronized boolean runOnce() {
        if (!settings.isEnabled()) return false;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("RAG worker must run outside a database transaction");
        }
        reconcileCleanup();
        ClaimedJob claimed = repository.claim(owner, settings.getLeaseSeconds());
        if (claimed == null) return false;
        switch (claimed.job().operation()) {
            case "BUILD" -> build(claimed);
            case "CLEANUP" -> cleanup(claimed);
            default -> repository.fail(claimed, "RAG_UNKNOWN_OPERATION", false);
        }
        return true;
    }

    private void build(ClaimedJob claimed) {
        boolean attemptedWrite = false;
        boolean committed = false;
        try {
            if (!settings.currentConfig().equals(claimed.config())) {
                repository.fail(claimed, "RAG_CONFIG_NOT_CURRENT", false);
                return;
            }
            List<IndexChunk> chunks = chunker.chunk(claimed);
            int start = 0;
            while (start < chunks.size()) {
                if (!renew(claimed)) return;
                List<String> input = nextInputBatch(claimed.title(), chunks, start);
                int end = start + input.size();
                List<IndexChunk> batch = chunks.subList(start, end);
                List<float[]> embedded = embeddings.embed(claimed.config(), input);
                List<RagVectorPoint> points = makePoints(claimed, batch, embedded);
                if (!renew(claimed)) return;
                // A request that times out may still reach Qdrant.
                attemptedWrite = true;
                vectors.upsert(claimed.config(), points);
                start = end;
            }
            committed = repository.finishBuild(claimed, chunks);
        } catch (RagIndexException error) {
            repository.fail(claimed, error.getErrorCode(), error.isRetryable());
        } catch (RuntimeException error) {
            recordUnexpectedFailure(claimed, error);
        } finally {
            if (attemptedWrite && !committed) {
                pendingCleanup = claimed;
                reconcileCleanup();
            }
        }
    }

    private void cleanup(ClaimedJob claimed) {
        try {
            if (!renew(claimed)) return;
            IndexJob job = claimed.job();
            vectors.deleteGeneration(claimed.config(), job.articleId(), job.revision(), job.generation());
            repository.finishCleanup(claimed);
        } catch (RagIndexException error) {
            repository.fail(claimed, error.getErrorCode(), error.isRetryable());
        } catch (RuntimeException error) {
            recordUnexpectedFailure(claimed, error);
        }
    }

    private boolean renew(ClaimedJob claimed) {
        return repository.renew(claimed, settings.getLeaseSeconds());
    }

    private void reconcileCleanup() {
        if (pendingCleanup != null) {
            repository.requeueAbandonedCleanup(pendingCleanup);
            pendingCleanup = null;
        }
    }

    private void recordUnexpectedFailure(ClaimedJob claimed, RuntimeException error) {
        log.warn("Knowledge index job failed, jobId={}, type={}", claimed.job().id(), error.getClass().getSimpleName());
        repository.fail(claimed, "RAG_WORKER_FAILURE", true);
    }

    private static List<RagVectorPoint> makePoints(ClaimedJob claimed, List<IndexChunk> chunks, List<float[]> embedded) {
        if (embedded == null || embedded.size() != chunks.size()) throw RagVectors.invalid();
        List<RagVectorPoint> points = new ArrayList<>(chunks.size());
        IndexJob job = claimed.job();
        for (int i = 0; i < chunks.size(); i++) {
            IndexChunk chunk = chunks.get(i);
            float[] vector = embedded.get(i);
            RagVectors.requireValid(vector, claimed.config().dimension());
            IndexCandidate candidate = new IndexCandidate(job.articleId(), job.revision(), job.configVersion(),
                    job.generation(), chunk.chunkId(), chunk.vectorId());
            points.add(new RagVectorPoint(candidate, vector));
        }
        return points;
    }

    private List<String> nextInputBatch(String title, List<IndexChunk> chunks, int start) {
        List<String> input = new ArrayList<>();
        long remaining = settings.getEmbeddingInputUnits() - RagIndexSettings.EMBEDDING_PROTOCOL_RESERVE;
        int limit = Math.min(chunks.size(), start + settings.getEmbeddingBatchSize());
        for (int index = start; index < limit; index++) {
            String text = embeddingText(title, chunks.get(index));
            long size = AiCallGovernor.utf8Bytes(text);
            if (size > remaining) break;
            remaining -= size;
            input.add(text);
        }
        if (input.isEmpty()) {
            throw new RagIndexException("400", "RAG_EMBEDDING_CHUNK_LIMIT", "单个索引分块超出向量服务输入预算", false);
        }
        return input;
    }

    private static String embeddingText(String title, IndexChunk chunk) {
        String articleTitle = title == null ? "" : title.strip();
        return chunk.section().isBlank() ? articleTitle + "\n" + chunk.content()
                : articleTitle + "\n" + chunk.section() + "\n" + chunk.content();
    }
}
