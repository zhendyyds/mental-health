package com.flj.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiProtectionProperties;
import com.flj.rag.*;
import com.flj.rag.qa.KnowledgeQaPrompt;
import com.flj.rag.qa.KnowledgeQaService;
import com.flj.rag.qa.QaFailure;
import com.flj.rag.qa.QaResponse;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed-corpus evaluation through the real index, retrieval, ledger, and answer service. */
class KnowledgeQaEvaluationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_TOKENS = 512;

    @Test
    @Timeout(240)
    void evaluateFixedCases() throws Exception {
        MysqlVerification db = new MysqlVerification();
        int port = Integer.parseInt(requiredEnv("BATCH6_QDRANT_PORT"));
        String mode = System.getenv().getOrDefault("BATCH6_EVAL_MODE", "fixture");
        assertTrue(Set.of("fixture", "real").contains(mode));
        boolean real = "real".equals(mode);
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path artifacts = Path.of(System.getenv().getOrDefault("BATCH6_EVAL_ARTIFACT_DIR",
                target.resolve("qa-eval-" + UUID.randomUUID()).toString())).toAbsolutePath().normalize();
        assertTrue(artifacts.startsWith(target) && !artifacts.equals(target));
        byte[] fixtureBytes;
        try (var input = new ClassPathResource("qa-eval/batch6-fixtures.json").getInputStream()) {
            fixtureBytes = input.readAllBytes();
        }
        Corpus corpus = JSON.readValue(fixtureBytes, Corpus.class);
        assertEquals("fictional", corpus.provenance());
        assertEquals(60, corpus.cases().size());
        assertEquals(12, corpus.cases().stream().filter(Sample::realHoldout).count());
        assertTrue(corpus.cases().stream().filter(Sample::realHoldout).allMatch(sample -> "holdout".equals(sample.split())));

        RagIndexSettings settings = settings(port, real, corpus.docs().size() + 1);
        ChatModel chat = real ? realChat() : KnowledgeQaEvaluationIT::fixtureChat;
        Files.createDirectories(artifacts);
        if (real) Files.createFile(artifacts.resolve("real-eval-attempt"));
        IndexConfig config = settings.currentConfig();
        KnowledgeIndexRepository index = new KnowledgeIndexRepository(db.jdbc,
                new DataSourceTransactionManager(db.dataSource), true, config.version());
        index.registerConfig(config);
        RagEmbeddingGateway delegate = real ? new SpringAiRagEmbeddingGateway(settings) : fixtureEmbeddings(corpus.docs());
        AtomicInteger embedCalls = new AtomicInteger();
        RagEmbeddingGateway embeddings = (cfg, texts) -> {
            embedCalls.incrementAndGet();
            return delegate.embed(cfg, texts);
        };
        Map<String, SourceDoc> sourcesByArticle = new HashMap<>();
        TrackedModel ragModel = new TrackedModel(chat);
        TrackedModel directModel = new TrackedModel(chat);
        try (QdrantClient server = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", port, false)
                .withTimeout(Duration.ofSeconds(5)).build());
             QdrantRagVectorGateway vectors = new QdrantRagVectorGateway(settings)) {
            assertEquals("1.16.3", server.healthCheckAsync().get(5, TimeUnit.SECONDS).getVersion());
            assertTrue(server.createCollectionAsync(config.collectionName(), VectorParams.newBuilder()
                    .setDistance(Distance.Cosine).setSize(config.dimension()).build()).get(5, TimeUnit.SECONDS).getResult());
            RagIndexWorker worker = new RagIndexWorker(index, settings, new RagChunker(), embeddings, vectors);
            for (SourceDoc source : corpus.docs()) {
                String articleId = UUID.randomUUID().toString();
                sourcesByArticle.put(articleId, source);
                db.jdbc.update("INSERT INTO knowledge_article (id,title,content,status) VALUES (?,?,?,1)",
                        articleId, source.title(), "<p>" + source.text() + "</p>");
                IndexJob job = index.reindex(articleId, config.version());
                assertTrue(worker.runOnce());
                assertEquals("SUCCEEDED", index.listJobs(articleId, 4).stream().filter(item -> item.id() == job.id()).findFirst().orElseThrow().status());
            }
            for (var source : sourcesByArticle.entrySet()) {
                if (source.getValue().offline()) {
                    db.jdbc.update("UPDATE knowledge_article SET status=2 WHERE id=?", source.getKey());
                    index.invalidate(source.getKey());
                }
            }
            assertEquals(corpus.docs().size(), server.getCollectionInfoAsync(config.collectionName())
                    .get(5, TimeUnit.SECONDS).getPointsCount(), "Offline points must remain physically present during evaluation");
            int indexingCalls = embedCalls.get();
            ObservedRetrieval retrieval = new ObservedRetrieval(index, settings, embeddings, vectors);
            AiProtectionProperties limits = new AiProtectionProperties();
            limits.setMaxOutputTokens(MAX_OUTPUT_TOKENS);
            List<CaseResult> results = new ArrayList<>();
            var directClient = ChatClient.builder(directModel).defaultTemplateRenderer(new NoOpTemplateRenderer())
                    .defaultSystem("""
                            回答当前问题。信息不足时明确拒答。只输出 JSON，例如 {"answer":"资料不足","insufficient":true}。
                            如果可以作答，在 answer 写答案并将 insufficient 设为 false。
                            """).build();
            try (KnowledgeQaService qa = new KnowledgeQaService(retrieval, new KnowledgeQaRequestRepository(db.jdbc),
                    new AiCallGovernor(limits), KnowledgeQaPrompt.client(ragModel), JSON)) {
                long userId = 70000;
                for (Sample sample : corpus.cases()) {
                    if (!real || sample.realHoldout()) {
                        results.add(runCase(qa, retrieval, real ? directClient : null, ++userId,
                                sample, sourcesByArticle, corpus.forbiddenText()));
                    }
                }
            }
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM ai_request_record WHERE scene='knowledge-qa' AND session_id IS NOT NULL", Integer.class));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("fixtureVersion", corpus.version());
            evidence.put("fixtureSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixtureBytes)));
            evidence.put("mode", mode);
            evidence.put("configuration", Map.of("collection", config.collectionName(), "dimension", config.dimension(),
                    "topK", settings.getTopK(), "threshold", settings.getScoreThreshold(), "maxOutputTokens", MAX_OUTPUT_TOKENS,
                    "chatModel", real ? "deepseek-flash" : "deterministic-first-sentence-stub",
                    "embeddingModel", real ? "text-embedding-v4" : "deterministic-alias-stub",
                    "temperature", 0.0));
            evidence.put("embeddingCalls", Map.of("index", indexingCalls, "query", embedCalls.get() - indexingCalls));
            evidence.put("ragUsage", ragModel.usage());
            evidence.put("directUsage", directModel.usage());
            evidence.put("all", metrics(results));
            evidence.put("holdout", metrics(results.stream().filter(result -> "holdout".equals(result.split())).toList()));
            if (!real) evidence.put("tune", metrics(results.stream().filter(result -> "tune".equals(result.split())).toList()));
            if (real) evidence.put("directComparison", directMetrics(results));
            evidence.put("cases", results);
            Path output = artifacts.resolve("qa-evaluation-" + mode + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
            System.out.println("QA evaluation evidence " + output);
            assertTrue(results.stream().noneMatch(result -> result.forbiddenOutput() || !result.citationsValid()), "A response exposed invalid evidence");
            if (real) {
                assertTrue(results.stream().anyMatch(result -> result.grounded() && result.supported()), "No verified real grounded answer");
                assertTrue(results.stream().allMatch(result -> result.error() == null && result.direct().error() == null), "Real evaluation recorded provider/format failures");
            } else {
                assertEquals(60, results.size());
                assertTrue(results.stream().allMatch(result -> result.error() == null &&
                        (result.answerable() ? result.grounded() && result.supported() : result.refused())));
                assertEquals(40, ragModel.usage().calls(), "No-evidence and offline fixtures must skip Chat");
            }
        }
    }

    private static CaseResult runCase(KnowledgeQaService qa, ObservedRetrieval retrieval, ChatClient direct, long userId,
                                      Sample sample, Map<String, SourceDoc> articles, List<String> forbidden) {
        long started = System.nanoTime();
        QaResponse response = null;
        String error = null;
        try {
            response = qa.ask(userId, sample.question(), UUID.randomUUID().toString());
        } catch (QaFailure failure) {
            error = failure.getCode() + "/" + failure.getStatus();
        }
        long totalMillis = elapsed(started);
        Trace trace = retrieval.traces.remove(userId);
        String answer = response == null ? "" : response.answer();
        boolean grounded = response != null && response.grounded();
        List<String> citationKeys = new ArrayList<>();
        int citationCount = response == null ? 0 : response.citations().size();
        boolean valid = grounded == (citationCount > 0);
        if (response != null) {
            for (var citation : response.citations()) {
                SourceDoc doc = articles.get(citation.articleId());
                if (doc == null || doc.offline() || trace == null || !trace.eligibleArticleIds().contains(citation.articleId())
                        || citation.revision() != 1 || !doc.text().contains(citation.excerpt()) || !doc.title().equals(citation.title())) {
                    valid = false;
                } else { citationKeys.add(doc.key()); }
            }
        }
        List<String> raw = trace == null ? List.of() : keys(trace.rawArticleIds(), articles);
        List<String> eligible = trace == null ? List.of() : keys(trace.eligibleArticleIds(), articles);
        boolean answerable = !sample.expectedSources().isEmpty();
        boolean matched = answerable && sample.expectedFacts().stream().allMatch(answer::contains);
        boolean supported = grounded && matched && valid && new HashSet<>(citationKeys).equals(new HashSet<>(sample.expectedSources()));
        DirectResult comparison = direct == null ? null : baseline(direct, sample);
        return new CaseResult(sample.id(), sample.group(), sample.split(), sample.expectedSources(), raw, eligible, citationKeys,
                answerable, grounded, response != null && !grounded, supported, valid, citationCount, forbidden.stream().anyMatch(answer::contains),
                answer, error, trace == null ? null : trace.millis(), totalMillis, comparison);
    }

    private static DirectResult baseline(ChatClient client, Sample sample) {
        long started = System.nanoTime();
        try {
            String text = client.prompt().user(sample.question()).call().content();
            JsonNode json = JSON.readTree(text);
            if (!json.path("answer").isTextual() || !json.path("insufficient").isBoolean()) {
                return new DirectResult("", false, false, elapsed(started), "FORMAT");
            }
            String answer = json.get("answer").asText();
            boolean refused = json.get("insufficient").asBoolean();
            boolean matched = !refused && !sample.expectedFacts().isEmpty() && sample.expectedFacts().stream().allMatch(answer::contains);
            return new DirectResult(answer, refused, matched, elapsed(started), null);
        } catch (Exception failure) {
            return new DirectResult("", false, false, elapsed(started), failure.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> metrics(List<CaseResult> results) {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<CaseResult> answerable = results.stream().filter(CaseResult::answerable).toList();
        int gold = answerable.stream().mapToInt(result -> result.goldSources().size()).sum();
        stats.put("samples", results.size());
        stats.put("answerable", answerable.size());
        stats.put("hitAtK", ratio(answerable.stream().filter(r -> r.goldSources().stream().anyMatch(r.rawSources()::contains)).count(), answerable.size()));
        stats.put("recallAtK", ratio(answerable.stream().flatMap(r -> r.goldSources().stream().filter(r.rawSources()::contains)).count(), gold));
        stats.put("grounded", results.stream().filter(CaseResult::grounded).count());
        stats.put("citationValidity", ratio(results.stream().mapToLong(r -> r.citations().size()).sum(), results.stream().mapToLong(CaseResult::citationCount).sum()));
        stats.put("evidenceSupport", ratio(results.stream().filter(CaseResult::supported).count(), results.stream().filter(CaseResult::grounded).count()));
        stats.put("correctRefusal", ratio(results.stream().filter(r -> !r.answerable() && r.refused()).count(), results.size() - answerable.size()));
        stats.put("falseRefusal", ratio(answerable.stream().filter(CaseResult::refused).count(), answerable.size()));
        stats.put("errors", results.stream().filter(r -> r.error() != null).count());
        stats.put("retrievalMillis", quantiles(results.stream().map(CaseResult::retrievalMillis).filter(java.util.Objects::nonNull).toList()));
        stats.put("totalMillis", quantiles(results.stream().map(CaseResult::totalMillis).toList()));
        return stats;
    }

    private static Map<String, Object> directMetrics(List<CaseResult> results) {
        long answerable = results.stream().filter(CaseResult::answerable).count();
        return Map.of("factsMatched", ratio(results.stream().filter(r -> r.direct().matched()).count(), answerable),
                "correctRefusal", ratio(results.stream().filter(r -> !r.answerable() && r.direct().refused()).count(), results.size() - answerable),
                "falseRefusal", ratio(results.stream().filter(r -> r.answerable() && r.direct().refused()).count(), answerable),
                "totalMillis", quantiles(results.stream().map(r -> r.direct().millis()).toList()));
    }

    private static Map<String, Long> quantiles(List<Long> values) {
        if (values.isEmpty()) return Map.of();
        List<Long> sorted = values.stream().sorted().toList();
        return Map.of("p50", sorted.get((int) Math.ceil(sorted.size() * .50) - 1),
                "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1));
    }

    private static Map<String, Long> ratio(long numerator, long denominator) {
        return Map.of("numerator", numerator, "denominator", denominator);
    }

    private static List<String> keys(List<String> ids, Map<String, SourceDoc> articles) {
        return ids.stream().map(articles::get).filter(java.util.Objects::nonNull).map(SourceDoc::key).distinct().toList();
    }

    private static RagIndexSettings settings(int port, boolean real, int fixtureDimension) {
        RagIndexSettings settings = new RagIndexSettings();
        settings.setEnabled(true);
        settings.setVersion("qa-evaluation-" + UUID.randomUUID().toString().substring(0, 8));
        settings.setCollectionName("qa_" + UUID.randomUUID().toString().substring(0, 8));
        settings.setQdrantPort(port);
        settings.setDimension(real ? 1024 : fixtureDimension);
        settings.setEmbeddingProvider(real ? "dashscope" : "openai-compatible");
        settings.setEmbeddingBaseUrl(real ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : "https://fixture.invalid/v1");
        if (real) settings.setEmbeddingApiKey(requiredEnv("DASHSCOPE_API_KEY"));
        return settings;
    }

    private static OpenAiChatModel realChat() {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        OpenAiApi api = OpenAiApi.builder().baseUrl("https://api.deepseek.com").completionsPath("/chat/completions")
                .apiKey(requiredEnv("DEEPSEEK_API_KEY")).restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
        return OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").maxTokens(MAX_OUTPUT_TOKENS)
                        .temperature(0.0).reasoningEffort("none")
                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null)).build()).build();
    }

    private static RagEmbeddingGateway fixtureEmbeddings(List<SourceDoc> docs) {
        return (config, inputs) -> inputs.stream().map(text -> {
            float[] vector = new float[config.dimension()];
            boolean hit = false;
            for (int i = 0; i < docs.size(); i++) {
                if (docs.get(i).aliases().stream().anyMatch(text::contains)) {
                    vector[i] = 1;
                    hit = true;
                }
            }
            if (!hit) vector[docs.size()] = 1;
            return vector;
        }).toList();
    }

    private static ChatResponse fixtureChat(Prompt prompt) {
        try {
            var excerpts = new ArrayList<Map<String, String>>();
            for (JsonNode source : JSON.readTree(prompt.getUserMessage().getText()).path("sources")) {
                String content = source.path("text").asText();
                int firstStop = content.indexOf('。');
                String quote = firstStop < 0 ? content : content.substring(0, firstStop + 1);
                excerpts.add(Map.of("sourceId", source.path("sourceId").asText(), "quote", quote));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(JSON.writeValueAsString(Map.of("excerpts", excerpts))))));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid fixture model input", error);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }

    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    private static final class ObservedRetrieval extends RagRetrievalService {
        private final RawVectors raw;
        private final Map<Long, Trace> traces = new ConcurrentHashMap<>();
        private ObservedRetrieval(KnowledgeIndexRepository index, RagIndexSettings settings, RagEmbeddingGateway embeddings, RagVectorGateway vectors) {
            this(index, settings, embeddings, new RawVectors(vectors));
        }
        private ObservedRetrieval(KnowledgeIndexRepository index, RagIndexSettings settings, RagEmbeddingGateway embeddings, RawVectors raw) {
            super(index, settings, embeddings, raw);
            this.raw = raw;
        }
        @Override public List<RagEvidence> retrieve(Long userId, String question) {
            long started = System.nanoTime();
            List<RagEvidence> evidence = List.of();
            try { return evidence = super.retrieve(userId, question); }
            finally {
                traces.put(userId, new Trace(raw.results.get(), evidence.stream().map(item -> item.candidate().articleId()).toList(), elapsed(started)));
                raw.results.remove();
            }
        }
    }

    private static final class RawVectors implements RagVectorGateway {
        private final RagVectorGateway delegate;
        private final ThreadLocal<List<String>> results = ThreadLocal.withInitial(List::of);
        private RawVectors(RagVectorGateway delegate) { this.delegate = delegate; }
        @Override public List<RagVectorMatch> search(IndexConfig config, float[] vector, int topK, double threshold) {
            List<RagVectorMatch> matches = delegate.search(config, vector, topK, threshold);
            results.set(matches.stream().map(match -> match.candidate().articleId()).toList());
            return matches;
        }
        @Override public void upsert(IndexConfig config, List<RagVectorPoint> points) { throw new UnsupportedOperationException(); }
        @Override public void deleteGeneration(IndexConfig config, String article, long revision, String generation) { throw new UnsupportedOperationException(); }
    }

    private static final class TrackedModel implements ChatModel {
        private final ChatModel delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger promptTokens = new AtomicInteger();
        private final AtomicInteger completionTokens = new AtomicInteger();
        private final AtomicInteger missing = new AtomicInteger();
        private TrackedModel(ChatModel delegate) { this.delegate = delegate; }
        @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
        @Override public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            ChatResponse response = delegate.call(prompt);
            var usage = response.getMetadata().getUsage();
            if (usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() == 0 || usage.getCompletionTokens() == null) {
                missing.incrementAndGet();
            } else {
                promptTokens.addAndGet(usage.getPromptTokens());
                completionTokens.addAndGet(usage.getCompletionTokens());
            }
            return response;
        }
        private Usage usage() { return new Usage(calls.get(), promptTokens.get(), completionTokens.get(), missing.get()); }
    }

    record Corpus(String version, String provenance, List<String> forbiddenText, List<SourceDoc> docs, List<Sample> cases) { }
    record SourceDoc(String key, String title, List<String> aliases, String text, boolean offline) { }
    record Sample(String id, String group, String split, boolean realHoldout, String question, List<String> expectedSources, List<String> expectedFacts) { }
    record Trace(List<String> rawArticleIds, List<String> eligibleArticleIds, long millis) { }
    record Usage(int calls, int promptTokens, int completionTokens, int missingUsageCalls) { }
    record DirectResult(String answer, boolean refused, boolean matched, long millis, String error) { }
    record CaseResult(String id, String group, String split, List<String> goldSources, List<String> rawSources, List<String> eligibleSources,
                      List<String> citations, boolean answerable, boolean grounded, boolean refused, boolean supported, boolean citationsValid,
                      int citationCount, boolean forbiddenOutput, String answer, String error, Long retrievalMillis, long totalMillis, DirectResult direct) { }
}
