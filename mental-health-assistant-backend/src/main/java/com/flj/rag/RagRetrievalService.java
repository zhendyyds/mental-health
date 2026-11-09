package com.flj.rag;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RagRetrievalService {
    private static final int QUESTION_MAX_LENGTH = 1000;
    private final KnowledgeIndexRepository repository;
    private final RagIndexSettings settings;
    private final RagEmbeddingGateway embeddings;
    private final RagVectorGateway vectors;

    public RagRetrievalService(KnowledgeIndexRepository repository, RagIndexSettings settings,
                               RagEmbeddingGateway embeddings, RagVectorGateway vectors) {
        this.repository = repository;
        this.settings = settings;
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    public List<RagEvidence> retrieve(Long userId, String question) {
        IndexConfig config = settings.currentConfig();
        if (userId == null || userId <= 0) throw new RagIndexException("401", "请先登录");
        if (question == null || question.isBlank() || question.length() > QUESTION_MAX_LENGTH) {
            throw new RagIndexException("400", "请输入不超过 1000 字的检索问题");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("RAG retrieval must run outside a database transaction");
        }
        List<float[]> embedded = embeddings.embed(config, List.of(question.strip()));
        if (embedded == null || embedded.size() != 1) throw RagVectors.invalid();
        float[] query = embedded.get(0);
        RagVectors.requireValid(query, config.dimension());
        int topK = settings.getTopK();
        double threshold = settings.getScoreThreshold();
        List<RagVectorMatch> matches = vectors.search(config, query, topK, threshold);
        Map<IndexCandidate, Double> ranked = new LinkedHashMap<>();
        for (RagVectorMatch match : matches.stream().limit(topK).toList()) {
            if (match != null && match.candidate() != null && config.version().equals(match.candidate().configVersion())
                    && Double.isFinite(match.score()) && match.score() >= (float) threshold && match.score() <= 1.0001) {
                ranked.putIfAbsent(match.candidate(), match.score());
            }
        }
        Map<IndexCandidate, ValidatedChunk> available = validated(List.copyOf(ranked.keySet()));
        Set<String> hashes = new HashSet<>();
        List<RagEvidence> evidence = new ArrayList<>();
        for (Map.Entry<IndexCandidate, Double> match : ranked.entrySet()) {
            ValidatedChunk chunk = available.get(match.getKey());
            if (chunk != null && hashes.add(chunk.contentHash())) {
                evidence.add(evidence(chunk, match.getValue()));
                if (evidence.size() == settings.getEvidenceLimit()) break;
            }
        }
        return List.copyOf(evidence);
    }

    /** Rechecks current article and chunk identity after generation or before replaying a saved answer. */
    public List<RagEvidence> revalidate(List<RagEvidence> evidence) {
        IndexConfig config = settings.currentConfig();
        List<RagEvidence> currentVersion = evidence.stream().filter(item ->
                config.version().equals(item.candidate().configVersion())).toList();
        Map<IndexCandidate, ValidatedChunk> available = validated(currentVersion.stream().map(RagEvidence::candidate).distinct().toList());
        List<RagEvidence> result = new ArrayList<>();
        for (RagEvidence item : currentVersion) {
            ValidatedChunk chunk = available.get(item.candidate());
            if (chunk != null && Objects.equals(item.contentHash(), chunk.contentHash())
                    && Objects.equals(item.content(), chunk.content())) {
                result.add(evidence(chunk, item.score()));
            }
        }
        return List.copyOf(result);
    }

    private Map<IndexCandidate, ValidatedChunk> validated(List<IndexCandidate> candidates) {
        if (candidates.isEmpty()) return Map.of();
        Map<IndexCandidate, ValidatedChunk> available = new LinkedHashMap<>();
        for (ValidatedChunk chunk : repository.validateCandidates(candidates)) {
            available.put(chunk.candidate(), chunk);
        }
        return available;
    }

    private static RagEvidence evidence(ValidatedChunk chunk, double score) {
        return new RagEvidence(chunk.candidate(), chunk.title(), chunk.section(), chunk.content(), chunk.contentHash(), score);
    }
}
