package com.flj.ragsmoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.governance.AiCallFailures;
import com.flj.util.HtmlSanitizer;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in, fixed fictional sample. No Spring application, MySQL, or production collection. */
class RagSmokeIT {
    static final String ARTICLE_ID = "fictional-study-break-v1";
    static final String TITLE = "虚构样文：学习间隙的短休息";
    static final String QUERY = "学习四十五分钟后，短休息可以做哪些具体动作？";
    static final String REAL_ORIGIN = "openai-compatible-response";
    static final String FIXTURE_ORIGIN = "synthetic-local-fixture";
    private static final String REAL_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String REAL_MODEL = "text-embedding-v4";
    private static final int REAL_DIMENSION = 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration GRPC_TIMEOUT = Duration.ofSeconds(5);
    private static final int TOP_K = 2;
    private static final String HTML = """
            <h2>学习间隙的短休息</h2>
            <p onclick="discardFakeHandler()">学习四十五分钟后，把屏幕移开，站起身慢走两分钟。回到座位前喝一小杯温水。</p>
            <script>discardFakeScript()</script>
            <p>如果走动不方便，可以坐直并轻轻转动肩膀，动作放慢，避免用力。</p>
            <p>休息结束时写下下一步的小任务，例如读完一页资料，再回到学习。</p>
            """;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(75)
    void storesAndRetrievesValidatedCitations() throws Exception {
        String phase = requiredEnv("RAG_SMOKE_PHASE");
        require(Set.of("write", "read", "fixture").contains(phase), "RAG_SMOKE_PHASE must be write, read, or fixture");
        String collection = requiredEnv("RAG_SMOKE_COLLECTION");
        require(collection.matches("healthagent_smoke_[a-z0-9_]{1,70}"), "Use a dedicated healthagent_smoke_ collection");
        int port = envNumber("RAG_SMOKE_QDRANT_PORT", 6334, 1, 65535);
        Path artifacts = artifactsDirectory(collection);
        Path checkpointFile = artifacts.resolve("checkpoint.json");
        List<Document> documents = sampleDocuments();
        String sampleSha256 = sampleSha256(documents);

        try (QdrantClient client = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", port, false)
                .withTimeout(GRPC_TIMEOUT).build())) {
            String qdrantVersion = client.healthCheckAsync().get().getVersion();
            Checkpoint checkpoint;
            QdrantVectorStore store;
            if (phase.equals("read")) {
                require(Files.isRegularFile(checkpointFile), "Restart checkpoint is missing");
                checkpoint = JSON.readValue(checkpointFile.toFile(), Checkpoint.class);
                require(collection.equals(checkpoint.collection()) && QUERY.equals(checkpoint.query())
                        && sampleSha256.equals(checkpoint.sampleSha256()), "Restart checkpoint does not match the collection and sample");
                require(Set.of(REAL_ORIGIN, FIXTURE_ORIGIN).contains(checkpoint.vectorOrigin()), "Unknown query vector provenance");
                if (REAL_ORIGIN.equals(checkpoint.vectorOrigin())) {
                    verifyRealConfiguration(checkpoint.embedding().endpoint(), checkpoint.embedding().requestedModel(), checkpoint.embedding().dimension());
                }
                verifyVector(checkpoint.queryVector(), checkpoint.embedding().dimension());
                var collectionInfo = verifyCollection(client, collection, checkpoint.embedding().dimension());
                require(collectionInfo.getPointsCount() == documents.size(), "Restarted collection point count does not match the fixture chunks");
                store = vectorStore(client, collection, Map.of(QUERY, checkpoint.queryVector()), checkpoint.embedding().dimension());
            } else {
                require(!Files.exists(checkpointFile) && !Files.exists(artifacts.resolve("retrieval.json")), "Artifact directory already contains a prior run");
                require(!client.collectionExistsAsync(collection).get(), "Collection already exists; use a fresh smoke collection");
                List<String> input = new ArrayList<>(documents.stream().map(Document::getText).toList());
                input.add(QUERY);
                Batch batch = phase.equals("fixture") ? fixtureBatch(input) : realBatch(input, artifacts);
                int dimension = batch.evidence().dimension();
                var created = client.createCollectionAsync(collection, VectorParams.newBuilder()
                        .setDistance(Distance.Cosine).setSize(dimension).build()).get();
                require(created.getResult(), "Qdrant did not create the test collection");
                verifyCollection(client, collection, dimension);
                store = vectorStore(client, collection, batch.vectors(), dimension);

                if (phase.equals("fixture")) {
                    assertThrows(IllegalStateException.class, () -> verifyCollection(client, collection, dimension + 1),
                            "A mismatched collection dimension must fail before vector search");
                    List<Document> emptyMatches = search(store);
                    require(emptyMatches.isEmpty(), "A newly created smoke collection should be empty");
                    assertThrows(IllegalStateException.class, () -> validatedSources(emptyMatches, documents),
                            "Empty retrieval must be rejected before generating an answer");
                }
                store.add(documents);
                require(client.getCollectionInfoAsync(collection).get().getPointsCount() == documents.size(),
                        "Qdrant point count does not match the fixture chunks");
                checkpoint = new Checkpoint(collection, QUERY, sampleSha256,
                        phase.equals("fixture") ? FIXTURE_ORIGIN : REAL_ORIGIN, batch.evidence(), batch.vectors().get(QUERY));
            }

            List<Source> sources = validatedSources(search(store), documents);
            require(sources.stream().anyMatch(source -> source.sourceId().equals("S1")), "Retrieved evidence did not include the sample's relevant chunk");
            Files.createDirectories(artifacts);
            if (!phase.equals("read")) { writeJson(checkpointFile, checkpoint); }
            Path retrieval = artifacts.resolve(phase.equals("read") ? "retrieval-restart.json" : "retrieval.json");
            writeJson(retrieval, new Retrieval(phase, collection, qdrantVersion, port, "Cosine", false,
                    QUERY, sampleSha256, checkpoint.vectorOrigin(), checkpoint.embedding(), phase.equals("write") ? 1 : 0,
                    CONNECT_TIMEOUT.toMillis(), RESPONSE_TIMEOUT.toMillis(), GRPC_TIMEOUT.toMillis(),
                    TOP_K, documents.size(), phase.equals("fixture"), sources));
            System.out.printf("RAG smoke: phase=%s, origin=%s, qdrant=%s, dimension=%d, sources=%d%n",
                    phase, checkpoint.vectorOrigin(), qdrantVersion, checkpoint.embedding().dimension(), sources.size());
        }
    }

    static List<Document> sampleDocuments() {
        String sanitized = HtmlSanitizer.sanitize(HTML);
        require(!sanitized.contains("discardFake"), "Unsafe fixture HTML was not removed by the article sanitizer");
        var paragraphs = Jsoup.parseBodyFragment(sanitized).select("p");
        require(paragraphs.size() == 3, "Unexpected paragraph count after article sanitization");
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            int index = i + 1;
            String text = paragraphs.get(i).text().strip();
            require(!text.isEmpty() && text.length() <= 120, "Fixture chunk exceeds the text budget");
            String id = UUID.nameUUIDFromBytes((ARTICLE_ID + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(new Document(id, text, Map.of("articleId", ARTICLE_ID, "title", TITLE,
                    "chunkIndex", index, "sourceId", "S" + index)));
        }
        return List.copyOf(documents);
    }

    static Batch requestEmbeddings(List<String> input, String baseUrl, String path, String key, String model, int expectedDimension) {
        String endpoint = endpoint(baseUrl, path);
        require(input.size() == 4 && input.stream().mapToInt(String::length).sum() <= 420, "Embedding sample exceeds its request budget");
        require(model.matches("[A-Za-z0-9_./:-]{1,100}"), "Invalid embedding model identifier");
        require(!endpoint.contains(key) && !model.equals(key), "Embedding credentials must be supplied only in the dedicated API key field");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(RESPONSE_TIMEOUT);
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).embeddingsPath(path).apiKey(key)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ResponseErrorHandler() {
                    @Override
                    public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }

                    @Override
                    public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
                        throw new EmbeddingHttpFailure(response.getStatusCode().value());
                    }
                }).build();
        var options = OpenAiEmbeddingOptions.builder().model(model).encodingFormat("float")
                .dimensions(expectedDimension > 0 ? expectedDimension : null).build();
        var embeddingModel = new OpenAiEmbeddingModel(api, MetadataMode.NONE, options, RetryTemplate.builder().maxAttempts(1).build());
        long started = System.nanoTime();
        EmbeddingResponse response;
        try {
            response = embeddingModel.call(new EmbeddingRequest(input, options));
        } catch (EmbeddingHttpFailure failure) {
            throw new IllegalStateException("Embedding provider returned HTTP " + failure.status + "; verify its endpoint, key, and model");
        } catch (RuntimeException failure) {
            if (AiCallFailures.isTimeout(failure)) { throw new IllegalStateException("Embedding provider timed out on its single request"); }
            throw new IllegalStateException("Embedding provider unavailable (" + failure.getClass().getSimpleName() + ")");
        }
        long duration = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, float[]> vectors = validatedVectors(input, response.getResults(), expectedDimension);
        String responseModel = response.getMetadata().getModel();
        // Metadata is evidence, not a place to copy arbitrary provider strings or credentials.
        if (responseModel == null || responseModel.contains(key) || !responseModel.matches("[A-Za-z0-9_./:-]{1,100}")) {
            responseModel = null;
        }
        Usage usage = response.getMetadata().getUsage();
        OpenAiApi.Usage nativeUsage = usage != null && usage.getNativeUsage() instanceof OpenAiApi.Usage reported ? reported : null;
        var evidence = new EmbeddingEvidence(endpoint, model, responseModel, vectors.get(QUERY).length, input.size(), duration,
                nativeUsage == null ? null : nativeUsage.promptTokens(), nativeUsage == null ? null : nativeUsage.totalTokens());
        return new Batch(vectors, evidence);
    }

    private static Batch realBatch(List<String> input, Path artifacts) throws IOException {
        String baseUrl = requiredEnv("RAG_SMOKE_EMBEDDING_BASE_URL");
        String path = env("RAG_SMOKE_EMBEDDING_PATH", "/v1/embeddings");
        String model = requiredEnv("RAG_SMOKE_EMBEDDING_MODEL");
        int dimension = envNumber("RAG_SMOKE_EMBEDDING_DIMENSIONS", 0, 1, 8192);
        String endpoint = endpoint(baseUrl, path);
        verifyRealConfiguration(endpoint, model, dimension);
        String key = requiredEnv("RAG_SMOKE_EMBEDDING_API_KEY");
        require(!endpoint.contains(key) && !model.equals(key), "Embedding credentials must be supplied only in the dedicated API key field");
        Files.createDirectories(artifacts);
        try {
            Files.createFile(artifacts.resolve("embedding-attempt"));
        } catch (FileAlreadyExistsException duplicate) {
            throw new IllegalStateException("An embedding attempt already exists for this run; inspect its result before explicitly starting another run");
        }
        return requestEmbeddings(input, baseUrl, path, key, model, dimension);
    }

    static void verifyRealConfiguration(String endpoint, String model, int dimension) {
        require(REAL_ENDPOINT.equals(endpoint) && REAL_MODEL.equals(model) && dimension == REAL_DIMENSION,
                "Real smoke requires the approved Beijing DashScope compatibility endpoint, text-embedding-v4, and 1024 dimensions");
    }

    static Map<String, float[]> validatedVectors(List<String> input, List<Embedding> results, int expectedDimension) {
        require(results.size() == input.size(), "Embedding response count does not match input count");
        Map<String, float[]> vectors = new HashMap<>();
        Set<Integer> indexes = new HashSet<>();
        int dimension = expectedDimension;
        for (Embedding embedding : results) {
            Integer index = embedding.getIndex();
            require(index != null && index >= 0 && index < input.size() && indexes.add(index), "Embedding response index missing, invalid, or duplicated");
            float[] vector = embedding.getOutput();
            if (dimension == 0 && vector != null) { dimension = vector.length; }
            verifyVector(vector, dimension);
            vectors.put(input.get(index), vector.clone());
        }
        require(vectors.size() == input.size(), "Fixture embedding input texts must be distinct");
        return Map.copyOf(vectors);
    }

    static void verifyVector(float[] vector, int dimension) {
        require(vector != null && dimension > 0 && dimension <= 8192 && vector.length == dimension,
                "Embedding dimension mismatch: expected " + dimension + ", got " + (vector == null ? 0 : vector.length));
        boolean nonzero = false;
        for (float value : vector) {
            require(Float.isFinite(value), "Embedding contains a non-finite component");
            nonzero |= value != 0f;
        }
        require(nonzero, "Embedding vector is all zeros");
    }

    private static Batch fixtureBatch(List<String> input) {
        var vectors = validatedVectors(input, List.of(new Embedding(new float[]{1, 0, 0}, 0),
                new Embedding(new float[]{0, 1, 0}, 1), new Embedding(new float[]{0, 0, 1}, 2),
                new Embedding(new float[]{0.9f, 0.1f, 0}, 3)), 3);
        return new Batch(vectors, new EmbeddingEvidence(null, null, null, 3, input.size(), 0, null, null));
    }

    private static CollectionInfo verifyCollection(QdrantClient client, String collection, int dimension) throws Exception {
        var info = client.getCollectionInfoAsync(collection).get();
        var vectors = info.getConfig().getParams().getVectorsConfig();
        require(vectors.hasParams(), "Smoke collection must use a single unnamed dense vector");
        require(vectors.getParams().getSize() == dimension, "Qdrant collection dimension mismatch: expected "
                + dimension + ", got " + vectors.getParams().getSize());
        require(vectors.getParams().getDistance() == Distance.Cosine, "Smoke collection must use cosine distance");
        return info;
    }

    private static QdrantVectorStore vectorStore(QdrantClient client, String collection, Map<String, float[]> vectors, int dimension) throws Exception {
        var store = QdrantVectorStore.builder(client, new CapturedVectors(vectors, dimension))
                .collectionName(collection).initializeSchema(false).build();
        store.afterPropertiesSet();
        return store;
    }

    private static List<Document> search(QdrantVectorStore store) {
        return store.similaritySearch(SearchRequest.builder().query(QUERY).topK(TOP_K)
                .similarityThreshold(0).filterExpression("articleId == '" + ARTICLE_ID + "'").build());
    }

    static List<Source> validatedSources(List<Document> matches, List<Document> original) {
        require(matches != null && !matches.isEmpty(), "Qdrant retrieval is empty; no citation or answer can be generated");
        Map<String, Document> allowed = new HashMap<>();
        original.forEach(document -> allowed.put(document.getId(), document));
        Set<String> seen = new HashSet<>();
        List<Source> sources = new ArrayList<>();
        for (Document match : matches) {
            Document expected = allowed.get(match.getId());
            require(expected != null && seen.add(match.getId()) && expected.getText().equals(match.getText()), "Retrieved point identity/text failed citation validation");
            Map<String, Object> metadata = match.getMetadata();
            for (String field : List.of("articleId", "title", "sourceId", "chunkIndex")) {
                require(Objects.equals(String.valueOf(expected.getMetadata().get(field)), String.valueOf(metadata.get(field))),
                        "Retrieved payload failed citation validation for " + field);
            }
            Double score = match.getScore();
            require(score != null && Double.isFinite(score) && score >= 0 && score <= 1.0001, "Retrieved score is not a valid cosine score");
            sources.add(new Source((String) metadata.get("sourceId"), ARTICLE_ID, TITLE,
                    ((Number) expected.getMetadata().get("chunkIndex")).intValue(), match.getText(), score, match.getId()));
        }
        return List.copyOf(sources);
    }

    private static String sampleSha256(List<Document> documents) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(QUERY.getBytes(StandardCharsets.UTF_8));
        for (Document document : documents) {
            digest.update(("\n" + document.getId() + "\n" + document.getText()).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String endpoint(String baseUrl, String path) {
        URI base;
        try { base = URI.create(baseUrl); }
        catch (IllegalArgumentException failure) { throw new IllegalStateException("Invalid embedding base URL"); }
        boolean local = Set.of("127.0.0.1", "localhost").contains(Objects.toString(base.getHost(), ""));
        require(base.getHost() != null && base.getRawUserInfo() == null && base.getRawQuery() == null && base.getRawFragment() == null
                && ("https".equals(base.getScheme()) || local && "http".equals(base.getScheme())), "Use an HTTPS embedding base URL without credentials or query parameters");
        require(!baseUrl.endsWith("/") && path.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"), "Use a base URL without trailing slash and an absolute embedding path");
        require(!(base.getPath().endsWith("/v1") && path.startsWith("/v1/")), "Embedding endpoint contains duplicate /v1 segments");
        return baseUrl + path;
    }

    private static Path artifactsDirectory(String collection) {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path path = Path.of(env("RAG_SMOKE_ARTIFACT_DIR", target.resolve("rag-smoke").resolve(collection).toString())).toAbsolutePath().normalize();
        require(path.startsWith(target) && !path.equals(target), "Smoke artifacts must be under backend/target");
        return path;
    }

    private static void writeJson(Path target, Object data) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), data);
    }

    private static String requiredEnv(String name) {
        String value = env(name, "");
        require(!value.isBlank(), "Missing " + name);
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int envNumber(String name, int fallback, int min, int max) {
        String value = env(name, "");
        if (value.isEmpty()) { return fallback; }
        int number;
        try { number = Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalStateException("Invalid " + name); }
        require(number >= min && number <= max, "Invalid " + name);
        return number;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalStateException(reason); }
    }

    private static final class EmbeddingHttpFailure extends RuntimeException {
        private final int status;
        private EmbeddingHttpFailure(int status) { this.status = status; }
    }

    /** Uses only vectors returned by the single real batch, or the visibly synthetic fixture. */
    private record CapturedVectors(Map<String, float[]> vectors, int dimensions) implements EmbeddingModel {
        @Override
        public float[] embed(Document document) { return vectorFor(document.getText()); }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (String text : request.getInstructions()) { embeddings.add(new Embedding(vectorFor(text), embeddings.size())); }
            return new EmbeddingResponse(embeddings);
        }

        private float[] vectorFor(String text) {
            float[] vector = vectors.get(text);
            require(vector != null, "No captured embedding for the exact sample text");
            return vector.clone();
        }
    }

    record Batch(Map<String, float[]> vectors, EmbeddingEvidence evidence) { }
    record EmbeddingEvidence(String endpoint, String requestedModel, String responseModel, int dimension, int inputCount,
                             long durationMillis, Integer promptTokens, Integer totalTokens) { }
    record Checkpoint(String collection, String query, String sampleSha256, String vectorOrigin,
                      EmbeddingEvidence embedding, float[] queryVector) { }
    record Source(String sourceId, String articleId, String title, int chunkIndex, String text, double score, String pointId) { }
    record Retrieval(String phase, String collection, String qdrantVersion, int grpcPort, String metric, boolean initializeSchema,
                     String query, String sampleSha256, String vectorOrigin, EmbeddingEvidence embedding, int embeddingRequestsThisPhase,
                     long embeddingConnectTimeoutMillis, long embeddingResponseTimeoutMillis, long grpcTimeoutMillis,
                     int topK, int chunkCount, boolean fixtureFailureChecks, List<Source> sources) { }
}
