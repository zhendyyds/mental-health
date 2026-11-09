package com.flj.rag;

import com.flj.AiService.governance.AiCallFailures;
import com.flj.AiService.governance.AiCallGovernor;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.AiService.governance.AiProtectionProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
public class SpringAiRagEmbeddingGateway implements RagEmbeddingGateway {
    // All indexing and retrieval embeddings share this process-local budget.
    private static final long INDEX_SYSTEM_BUCKET = 1L;
    private final RagIndexSettings settings;
    private final String endpoint;
    private final String provider;
    private final EmbeddingModel model;
    private final AiCallGovernor governor;

    @Autowired
    public SpringAiRagEmbeddingGateway(RagIndexSettings settings) {
        this(settings, settings.isEnabled() ? createModel(settings) : null);
    }

    SpringAiRagEmbeddingGateway(RagIndexSettings settings, EmbeddingModel model) {
        this.settings = settings;
        this.endpoint = settings.isEnabled() ? settings.configuredEmbeddingEndpoint() : null;
        this.provider = settings.getEmbeddingProvider();
        this.model = model;
        this.governor = settings.isEnabled() ? createGovernor(settings) : null;
    }

    @Override
    public List<float[]> embed(IndexConfig config, List<String> texts) {
        settings.requireEnabled();
        if (!provider.equals(config.embeddingProvider()) || !endpoint.equals(config.embeddingEndpoint())) {
            throw new RagIndexException("503", "RAG_EMBEDDING_CONFIG_MISMATCH", "索引配置与当前向量服务凭据不匹配", false);
        }
        if (texts == null || texts.isEmpty() || texts.size() > settings.getEmbeddingBatchSize()
                || texts.size() > 10 || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new RagIndexException("400", "RAG_EMBEDDING_INPUT", "向量请求的文本批次无效", false);
        }
        var options = OpenAiEmbeddingOptions.builder().model(config.embeddingModel())
                .dimensions(config.dimension()).encodingFormat("float").build();
        try (AiCallGovernor.Lease lease = governor.acquire(INDEX_SYSTEM_BUCKET, "rag-embedding")) {
            lease.validateInput(texts.toArray(String[]::new));
            lease.markStarted();
            EmbeddingResponse response = model.call(new EmbeddingRequest(texts, options));
            return orderedVectors(response, texts.size(), config.dimension());
        } catch (AiCallRejectedException error) {
            throw new RagIndexException(error.getCode(), "RAG_EMBEDDING_BUDGET", error.getMessage(), "429".equals(error.getCode()));
        } catch (EmbeddingHttpFailure error) {
            boolean retryable = error.status == 408 || error.status == 429 || error.status >= 500;
            String code = error.status == 408 || error.status == 504 ? "504" : error.status == 429 ? "429" : "502";
            throw new RagIndexException(code, "RAG_EMBEDDING_HTTP_" + error.status, "向量服务未能完成请求", retryable);
        } catch (RagIndexException error) {
            throw error;
        } catch (RuntimeException error) {
            if (AiCallFailures.isTimeout(error)) {
                throw new RagIndexException("504", "RAG_EMBEDDING_TIMEOUT", "向量服务请求超时", true);
            }
            throw new RagIndexException("502", "RAG_EMBEDDING_UNAVAILABLE", "向量服务暂不可用", true);
        }
    }

    static List<float[]> orderedVectors(EmbeddingResponse response, int count, int dimension) {
        if (response == null || response.getResults() == null || response.getResults().size() != count) {
            throw RagVectors.invalid();
        }
        float[][] ordered = new float[count][];
        for (Embedding embedding : response.getResults()) {
            if (embedding == null) throw RagVectors.invalid();
            Integer index = embedding.getIndex();
            if (index == null || index < 0 || index >= count || ordered[index] != null) throw RagVectors.invalid();
            float[] vector = embedding.getOutput();
            RagVectors.requireValid(vector, dimension);
            ordered[index] = vector.clone();
        }
        return List.copyOf(Arrays.asList(ordered));
    }

    private static AiCallGovernor createGovernor(RagIndexSettings settings) {
        AiProtectionProperties limits = new AiProtectionProperties();
        limits.setMaxConcurrent(settings.getEmbeddingMaxConcurrent());
        limits.setUserRequestsPerMinute(settings.getEmbeddingRequestsPerMinute());
        limits.setUserDailyCalls(settings.getEmbeddingDailyCalls());
        limits.setGlobalDailyCalls(settings.getEmbeddingDailyCalls());
        limits.setMaxTrackedUsers(1);
        limits.setMaxInputUnits(settings.getEmbeddingInputUnits());
        limits.setReservedInputUnits(RagIndexSettings.EMBEDDING_PROTOCOL_RESERVE);
        return new AiCallGovernor(limits);
    }

    private static EmbeddingModel createModel(RagIndexSettings settings) {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.getEmbeddingConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(settings.getEmbeddingReadTimeoutMs()));
        OpenAiApi api = OpenAiApi.builder().baseUrl(settings.getEmbeddingBaseUrl())
                .embeddingsPath(settings.getEmbeddingPath()).apiKey(settings.getEmbeddingApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ResponseErrorHandler() {
                    @Override
                    public boolean hasError(ClientHttpResponse response) throws IOException {
                        return !response.getStatusCode().is2xxSuccessful();
                    }

                    @Override
                    public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
                        throw new EmbeddingHttpFailure(response.getStatusCode().value());
                    }
                }).build();
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(settings.getEmbeddingModel()).encodingFormat("float").build(),
                RetryTemplate.builder().maxAttempts(1).build());
    }

    private static final class EmbeddingHttpFailure extends RuntimeException {
        private final int status;

        private EmbeddingHttpFailure(int status) {
            this.status = status;
        }
    }
}
