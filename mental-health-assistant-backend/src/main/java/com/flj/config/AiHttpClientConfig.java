package com.flj.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** HTTP builders consumed by Spring AI 1.0.0's OpenAI auto-configuration. */
@Configuration(proxyBeanMethods = false)
public class AiHttpClientConfig {
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public AiHttpClientConfig(
            @Value("${app.ai.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.ai.http.read-timeout:55s}") Duration readTimeout) {
        Assert.isTrue(connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()
                && connectTimeout.compareTo(Duration.ofSeconds(5)) <= 0, "AI connect timeout must be within 5 seconds");
        Assert.isTrue(readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative()
                && readTimeout.compareTo(Duration.ofSeconds(55)) <= 0, "AI read timeout must be within 55 seconds");
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Bean
    public HttpClient aiHttpClient() {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder aiRestClientBuilder(@Qualifier("aiHttpClient") HttpClient client) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder aiWebClientBuilder(@Qualifier("aiHttpClient") HttpClient client) {
        JdkClientHttpConnector connector = new JdkClientHttpConnector(client);
        connector.setReadTimeout(readTimeout);
        return WebClient.builder().clientConnector(connector)
                // The JDK request timeout covers response headers; keep an idle
                // deadline on the response body as well, including streaming SSE.
                .filter((request, next) -> next.exchange(request).map(response ->
                        response.mutate().body(body -> body.timeout(readTimeout)).build()));
    }

    @Bean
    public RetryTemplate aiRetryTemplate() {
        // A timeout may occur after the provider accepted a paid request.
        return RetryTemplate.builder().maxAttempts(1).build();
    }
}
