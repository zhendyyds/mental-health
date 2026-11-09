package com.flj.rag;

import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpdateStatus;
import io.qdrant.client.grpc.Points.UpsertPoints;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.include;

@Component
public class QdrantRagVectorGateway implements RagVectorGateway, AutoCloseable {
    private static final String ARTICLE = "articleId";
    private static final String REVISION = "revision";
    private static final String CONFIG = "indexConfigVersion";
    private static final String GENERATION = "indexGeneration";
    private static final String CHUNK = "chunkId";
    private static final List<String> PAYLOAD_FIELDS = List.of(ARTICLE, REVISION, CONFIG, GENERATION, CHUNK);

    private final RagIndexSettings settings;
    private final Duration timeout;
    private final QdrantClient client;

    public QdrantRagVectorGateway(RagIndexSettings settings) {
        this.settings = settings;
        this.timeout = Duration.ofMillis(settings.getQdrantTimeoutMs());
        this.client = settings.isEnabled() ? connect(settings, timeout) : null;
    }

    @Override
    public void upsert(IndexConfig config, List<RagVectorPoint> points) {
        if (points == null || points.isEmpty() || points.size() > 10) throw RagVectors.invalid();
        List<PointStruct> writes = new ArrayList<>(points.size());
        for (RagVectorPoint point : points) {
            IndexCandidate candidate = point.candidate();
            if (!config.version().equals(candidate.configVersion())) throw RagVectors.invalid();
            RagVectors.requireValid(point.vector(), config.dimension());
            writes.add(PointStruct.newBuilder().setId(id(UUID.fromString(candidate.vectorId())))
                    .setVectors(vectors(point.vector())).putAllPayload(Map.of(
                            ARTICLE, value(candidate.articleId()), REVISION, value(candidate.revision()),
                            CONFIG, value(candidate.configVersion()), GENERATION, value(candidate.generation()),
                            CHUNK, value(candidate.chunkId()))).build());
        }
        verifyCollection(config);
        var request = UpsertPoints.newBuilder().setCollectionName(config.collectionName())
                .setWait(true).addAllPoints(writes).build();
        requireCompleted(await(client.upsertAsync(request, timeout)));
    }

    @Override
    public List<RagVectorMatch> search(IndexConfig config, float[] vector, int topK, double scoreThreshold) {
        RagVectors.requireValid(vector, config.dimension());
        if (topK < 1 || topK > 50 || !Double.isFinite(scoreThreshold) || scoreThreshold < 0 || scoreThreshold > 1) {
            throw new RagIndexException("400", "RAG_SEARCH_LIMITS", "检索参数无效", false);
        }
        verifyCollection(config);
        List<Float> components = new ArrayList<>(vector.length);
        for (float component : vector) components.add(component);
        var request = SearchPoints.newBuilder().setCollectionName(config.collectionName())
                .addAllVector(components).setLimit(topK).setScoreThreshold((float) scoreThreshold)
                .setWithPayload(include(PAYLOAD_FIELDS))
                .setFilter(Filter.newBuilder().addMust(matchKeyword(CONFIG, config.version())))
                .build();
        List<RagVectorMatch> matches = new ArrayList<>();
        for (ScoredPoint point : await(client.searchAsync(request, timeout))) {
            RagVectorMatch match = parseMatch(point, config.version(), scoreThreshold);
            if (match != null) matches.add(match);
        }
        return List.copyOf(matches);
    }

    @Override
    public void deleteGeneration(IndexConfig config, String articleId, long revision, String generation) {
        try {
            verifyCollection(config);
            Filter filter = Filter.newBuilder()
                    .addMust(matchKeyword(ARTICLE, articleId))
                    .addMust(match(REVISION, revision))
                    .addMust(matchKeyword(CONFIG, config.version()))
                    .addMust(matchKeyword(GENERATION, generation)).build();
            var request = DeletePoints.newBuilder().setCollectionName(config.collectionName()).setWait(true)
                    .setPoints(PointsSelector.newBuilder().setFilter(filter)).build();
            requireCompleted(await(client.deleteAsync(request, timeout)));
        } catch (RagIndexException error) {
            // An operator may have removed an unused version's entire collection.
            if (!"RAG_QDRANT_COLLECTION_MISSING".equals(error.getErrorCode())) throw error;
        }
    }

    private void verifyCollection(IndexConfig config) {
        settings.requireEnabled();
        var params = await(client.getCollectionInfoAsync(config.collectionName(), timeout))
                .getConfig().getParams().getVectorsConfig();
        if (!params.hasParams() || params.getParams().getSize() != config.dimension()
                || params.getParams().getDistance() != Distance.Cosine) {
            throw new RagIndexException("503", "RAG_QDRANT_SCHEMA", "向量集合必须预建为匹配维度的余弦集合", false);
        }
    }

    static RagVectorMatch parseMatch(ScoredPoint point, String configVersion, double threshold) {
        Map<String, Value> payload = point.getPayloadMap();
        String articleId = stringValue(payload, ARTICLE, 64);
        String chunkId = stringValue(payload, CHUNK, 36);
        String generation = stringValue(payload, GENERATION, 36);
        String version = stringValue(payload, CONFIG, 64);
        Value revision = payload.get(REVISION);
        double score = point.getScore();
        if (articleId == null || chunkId == null || generation == null || !configVersion.equals(version)
                || revision == null || revision.getKindCase() != Value.KindCase.INTEGER_VALUE || revision.getIntegerValue() < 1
                || !point.getId().hasUuid() || !Double.isFinite(score) || score < (float) threshold || score > 1.0001) {
            return null;
        }
        String vectorId;
        try {
            vectorId = UUID.fromString(point.getId().getUuid()).toString();
        } catch (IllegalArgumentException error) {
            return null;
        }
        return new RagVectorMatch(new IndexCandidate(articleId, revision.getIntegerValue(), version,
                generation, chunkId, vectorId), score);
    }

    private static String stringValue(Map<String, Value> payload, String field, int maxLength) {
        Value value = payload.get(field);
        if (value == null || value.getKindCase() != Value.KindCase.STRING_VALUE) return null;
        String text = value.getStringValue();
        return text.isBlank() || text.length() > maxLength ? null : text;
    }

    private static void requireCompleted(UpdateResult result) {
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new RagIndexException("502", "RAG_QDRANT_INCOMPLETE", "向量集合未确认操作完成", true);
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw timedOut();
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RagIndexException("503", "RAG_QDRANT_INTERRUPTED", "向量集合操作已中断", true);
        } catch (ExecutionException error) {
            Status.Code code = Status.fromThrowable(error.getCause()).getCode();
            if (code == Status.Code.DEADLINE_EXCEEDED) throw timedOut();
            if (code == Status.Code.NOT_FOUND) {
                throw new RagIndexException("503", "RAG_QDRANT_COLLECTION_MISSING", "向量集合尚未预建", false);
            }
            boolean retryable = code != Status.Code.INVALID_ARGUMENT && code != Status.Code.PERMISSION_DENIED
                    && code != Status.Code.UNAUTHENTICATED;
            throw new RagIndexException("502", "RAG_QDRANT_UNAVAILABLE", "向量集合暂不可用", retryable);
        }
    }

    private static RagIndexException timedOut() {
        return new RagIndexException("504", "RAG_QDRANT_TIMEOUT", "向量集合操作超时", true);
    }

    private static QdrantClient connect(RagIndexSettings settings, Duration timeout) {
        // 1.13.0's constructor-time compatibility probe has no deadline. Request-time checks below do.
        var builder = QdrantGrpcClient.newBuilder(settings.getQdrantHost(), settings.getQdrantPort(), settings.isQdrantTls(), false)
                .withTimeout(timeout);
        String key = settings.getQdrantApiKey();
        if (key != null && !key.isBlank()) builder.withApiKey(key);
        return new QdrantClient(builder.build());
    }

    @PreDestroy
    @Override
    public void close() {
        if (client != null) client.close();
    }
}
