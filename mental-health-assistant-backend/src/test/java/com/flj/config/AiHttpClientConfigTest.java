package com.flj.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiHttpClientConfigTest {
    private HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "ai-http-stub");
        thread.setDaemon(true);
        return thread;
    });

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(AiHttpClientConfig.class);

    @AfterEach
    void closeStub() {
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void connectionTimeoutIsFiveSecondsAndInvalidLimitsFailFast() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HttpClient.class).connectTimeout()).contains(Duration.ofSeconds(5));
        });
        assertThatThrownBy(() -> new AiHttpClientConfig(Duration.ofSeconds(6), Duration.ofSeconds(55)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiHttpClientConfig(Duration.ofSeconds(5), Duration.ofSeconds(56)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void synchronousAndReactiveBuildersConnectToLocalStubAndReadTimeOut() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startStub(exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(700); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body = "fixture".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        runner.withPropertyValues("app.ai.http.read-timeout=150ms").run(context -> {
            assertThat(context).hasNotFailed();
            RestClient sync = context.getBean(RestClient.Builder.class).baseUrl(baseUrl()).build();
            assertThatThrownBy(() -> sync.get().uri("/fixture").retrieve().body(String.class))
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
            WebClient reactive = context.getBean(WebClient.Builder.class).baseUrl(baseUrl()).build();
            assertThatThrownBy(() -> reactive.get().uri("/fixture").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(2)))
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(requests).hasValue(2);
        });
    }

    @Test
    void responseBodyStallTimesOutEvenAfterHeadersAndFirstChunkArrive() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startStub(exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("first".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(700); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        runner.withPropertyValues("app.ai.http.read-timeout=150ms").run(context -> {
            RestClient sync = context.getBean(RestClient.Builder.class).baseUrl(baseUrl()).build();
            assertThatThrownBy(() -> sync.get().uri("/fixture").retrieve().body(String.class))
                    .isInstanceOf(RuntimeException.class);
            WebClient reactive = context.getBean(WebClient.Builder.class).baseUrl(baseUrl()).build();
            assertThatThrownBy(() -> reactive.get().uri("/fixture").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(2)))
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(requests).hasValue(2);
        });
    }

    @Test
    void continuousSseMayExceedReadIdleTimeoutWhenEveryFrameArrivesInTime() throws IOException {
        startStub(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int index = 0; index < 8; index++) {
                    exchange.getResponseBody().write(("data: chunk-" + index + "\n\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            } finally { exchange.close(); }
        });
        runner.withPropertyValues("app.ai.http.read-timeout=500ms").run(context -> {
            WebClient reactive = context.getBean(WebClient.Builder.class).baseUrl(baseUrl()).build();
            long started = System.nanoTime();
            String body = reactive.get().uri("/fixture").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
            assertThat(body).isNotNull();
            for (int index = 0; index < 8; index++) assertThat(body).contains("chunk-" + index);
            assertThat(body.split("chunk-", -1)).hasSize(9);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(500));
        });
    }

    @Test
    void publicConfigurationResolvesEnvironmentBeforePrivateLocalDefaults() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("public-config", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-local", Map.of(
                "local.spring.datasource.password", "fixture-local-password", "local.jwt.secret", "fixture-local-signing")));
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("fixture-local-password");
        assertThat(environment.getProperty("jwt.secret")).isEqualTo("fixture-local-signing");
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-environment", Map.of(
                "DB_PASSWORD", "fixture-environment-password", "JWT_SECRET", "fixture-environment-signing")));
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("fixture-environment-password");
        assertThat(environment.getProperty("jwt.secret")).isEqualTo("fixture-environment-signing");
        assertThat(environment.getProperty("jwt.header")).isEqualTo("token");
        assertThat(environment.getProperty("jwt.token-prefix")).isEmpty();
    }

    @Test
    void springAiAutoConfigurationUsesOurTimeoutsAndNeverRetriesPaidRequests() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startStub(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"fixture unavailable\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        aiRunner().run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiChatModel model = context.getBean(OpenAiChatModel.class);
            assertThatThrownBy(() -> model.call(new Prompt("fixture question"))).isInstanceOf(RuntimeException.class);
            assertThat(requests).hasValue(1);
            assertThatThrownBy(() -> model.stream(new Prompt("fixture question")).collectList().block(Duration.ofSeconds(2)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(requests).hasValue(2);
        });
    }

    @Test
    void actualAutoConfiguredModelAlsoHonorsReadTimeoutWithoutRetry() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startStub(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(700); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        aiRunner().withPropertyValues("app.ai.http.read-timeout=150ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(OpenAiChatModel.class).call(new Prompt("fixture question")))
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(requests).hasValue(1);
        });
    }

    private ApplicationContextRunner aiRunner() {
        return runner.withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withPropertyValues("spring.ai.openai.api-key=fixture-key", "spring.ai.openai.base-url=" + baseUrl(),
                        "spring.ai.openai.chat.options.model=fixture-model");
    }

    private void startStub(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(executor);
        server.start();
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
