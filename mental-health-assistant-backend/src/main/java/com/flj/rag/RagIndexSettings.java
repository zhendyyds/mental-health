package com.flj.rag;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagIndexSettings {
    public static final String EXTRACTOR_VERSION = "jsoup-plain-v1";
    public static final String CHUNKER_VERSION = "section-overlap-v1";
    static final int EMBEDDING_PROTOCOL_RESERVE = 512;
    private static final Set<Integer> DASHSCOPE_V4_DIMENSIONS = Set.of(64, 128, 256, 512, 768, 1024, 1536, 2048);
    private static final Set<String> DASHSCOPE_HOSTS = Set.of(
            "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "cn-hongkong.dashscope.aliyuncs.com");

    private boolean enabled;
    private String version;
    private String collectionName;
    private String embeddingProvider = "dashscope";
    private String embeddingBaseUrl;
    private String embeddingPath = "/embeddings";
    private String embeddingApiKey;
    private String embeddingModel = "text-embedding-v4";
    private int dimension = 1024;
    private int chunkSize = 600;
    private int chunkOverlap = 100;
    private int embeddingBatchSize = 8;
    private int embeddingInputUnits = 32768;
    private int embeddingMaxConcurrent = 2;
    private int embeddingRequestsPerMinute = 30;
    private int embeddingDailyCalls = 300;
    private int embeddingConnectTimeoutMs = 5000;
    private int embeddingReadTimeoutMs = 25000;
    private String qdrantHost = "127.0.0.1";
    private int qdrantPort = 6334;
    private boolean qdrantTls;
    private String qdrantApiKey;
    private int qdrantTimeoutMs = 5000;
    private int leaseSeconds = 60;
    private int pollIntervalMs = 2000;
    private int topK = 10;
    private int evidenceLimit = 6;
    private double scoreThreshold = 0.30;

    @PostConstruct
    void validateEnabledConfiguration() {
        if (!enabled) return;
        currentConfig();
        require(validKey(embeddingApiKey), "embedding-api-key");
        require(qdrantHost != null && qdrantHost.matches("[A-Za-z0-9.:-]{1,253}"), "qdrant-host");
        require(qdrantPort >= 1 && qdrantPort <= 65535, "qdrant-port");
        require(qdrantApiKey == null || qdrantApiKey.isBlank() || qdrantTls && validKey(qdrantApiKey), "qdrant-api-key/tls");
        require(embeddingBatchSize >= 1 && embeddingBatchSize <= 10, "embedding-batch-size");
        require(embeddingInputUnits >= 1024 && embeddingInputUnits <= 65536, "embedding-input-units");
        require(embeddingMaxConcurrent >= 1 && embeddingMaxConcurrent <= 8, "embedding-max-concurrent");
        require(embeddingRequestsPerMinute >= 1 && embeddingRequestsPerMinute <= 1000, "embedding-requests-per-minute");
        require(embeddingDailyCalls >= 1 && embeddingDailyCalls <= 10000, "embedding-daily-calls");
        require(embeddingConnectTimeoutMs >= 100 && embeddingConnectTimeoutMs <= 10000, "embedding-connect-timeout-ms");
        require(embeddingReadTimeoutMs >= 100 && embeddingReadTimeoutMs <= 60000, "embedding-read-timeout-ms");
        require(qdrantTimeoutMs >= 100 && qdrantTimeoutMs <= 15000, "qdrant-timeout-ms");
        require(leaseSeconds <= 180 && leaseSeconds * 1000L >
                Math.max(embeddingConnectTimeoutMs + embeddingReadTimeoutMs, qdrantTimeoutMs * 2L) + 5000L, "lease-seconds");
        require(pollIntervalMs >= 500 && pollIntervalMs <= 300000, "poll-interval-ms");
        require(topK >= 1 && topK <= 50 && evidenceLimit >= 1 && evidenceLimit <= 10 && evidenceLimit <= topK, "top-k/evidence-limit");
        require(Double.isFinite(scoreThreshold) && scoreThreshold >= 0 && scoreThreshold <= 1, "score-threshold");
    }

    public IndexConfig currentConfig() {
        requireEnabled();
        require(version != null && version.matches("[A-Za-z0-9_.-]{1,64}"), "version");
        require(collectionName != null && collectionName.matches("[A-Za-z0-9_-]{1,63}"), "collection-name");
        require(Set.of("dashscope", "openai-compatible").contains(embeddingProvider), "embedding-provider");
        require(embeddingModel != null && embeddingModel.matches("[A-Za-z0-9_./:-]{1,100}"), "embedding-model");
        require(dimension >= 1 && dimension <= 8192, "dimension");
        if ("dashscope".equals(embeddingProvider) && "text-embedding-v4".equals(embeddingModel)) {
            require(DASHSCOPE_V4_DIMENSIONS.contains(dimension), "dimension");
        }
        require(chunkSize >= 400 && chunkSize <= 800 && chunkOverlap >= 80 && chunkOverlap <= 120, "chunk-size/chunk-overlap");
        String endpoint = configuredEmbeddingEndpoint();
        return new IndexConfig(version, embeddingProvider, endpoint, embeddingModel, dimension, collectionName,
                EXTRACTOR_VERSION, CHUNKER_VERSION, chunkSize, chunkOverlap);
    }

    String configuredEmbeddingEndpoint() {
        require(embeddingBaseUrl != null && !embeddingBaseUrl.endsWith("/"), "embedding-base-url");
        require(embeddingPath != null && embeddingPath.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"), "embedding-path");
        URI base;
        try {
            base = URI.create(embeddingBaseUrl);
        } catch (IllegalArgumentException error) {
            throw badSetting("embedding-base-url");
        }
        require("https".equals(base.getScheme()) && base.getHost() != null
                && (base.getPort() == -1 || base.getPort() == 443) && base.getRawUserInfo() == null
                && base.getRawQuery() == null && base.getRawFragment() == null, "embedding-base-url");
        require(!(base.getPath().endsWith("/v1") && embeddingPath.startsWith("/v1/")), "embedding-path");
        if ("dashscope".equals(embeddingProvider)) {
            String host = base.getHost();
            boolean workspaceHost = host.matches("[a-z0-9-]+[.](cn-beijing|ap-southeast-1|cn-hongkong)[.]maas[.]aliyuncs[.]com");
            require(DASHSCOPE_HOSTS.contains(host) || workspaceHost, "embedding-base-url");
            require("/compatible-mode/v1".equals(base.getPath()) && "/embeddings".equals(embeddingPath), "embedding-path");
        }
        String endpoint = embeddingBaseUrl + embeddingPath;
        require(endpoint.length() <= 255, "embedding-base-url/embedding-path");
        return endpoint;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new RagIndexException("503", "RAG_DISABLED", "知识索引尚未启用", false);
        }
    }

    private static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.chars().noneMatch(Character::isWhitespace);
    }

    private static void require(boolean valid, String property) {
        if (!valid) throw badSetting(property);
    }

    private static RagIndexException badSetting(String property) {
        return new RagIndexException("503", "RAG_CONFIGURATION", "知识索引配置无效 " + property, false);
    }
}
