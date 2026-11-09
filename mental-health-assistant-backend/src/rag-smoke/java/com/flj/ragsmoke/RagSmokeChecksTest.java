package com.flj.ragsmoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture responses cover request contracts and failures; they do not prove provider compatibility. */
class RagSmokeChecksTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "fictional-fixture-key";
    private static final String MODEL = "fixture-embedding";
    private static final String PATH = "/v1/embeddings";

    @Test
    void usesSingleBatchWithExplicitPathModelDimensionAndReportedUsage() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean authorized = new AtomicBoolean();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode" + PATH, exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                authorized.set(("Bearer " + API_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                body.set(JSON.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"object":"list","model":"fixture-response-model","usage":{"prompt_tokens":31,"total_tokens":33},
                         "data":[{"object":"embedding","index":3,"embedding":[4,5,6]},
                                 {"object":"embedding","index":1,"embedding":[0,1,0]},
                                 {"object":"embedding","index":0,"embedding":[1,0,0]},
                                 {"object":"embedding","index":2,"embedding":[0,0,1]}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            String baseUrl = baseUrl(server) + "/compatible-mode";
            var batch = RagSmokeIT.requestEmbeddings(inputs(), baseUrl, PATH, API_KEY, MODEL, 3);
            assertEquals(1, requests.get());
            assertTrue(authorized.get());
            assertEquals(4, body.get().path("input").size());
            assertEquals(RagSmokeIT.QUERY, body.get().path("input").get(3).asText());
            assertEquals(MODEL, body.get().path("model").asText());
            assertEquals(3, body.get().path("dimensions").asInt());
            assertArrayEquals(new float[]{4, 5, 6}, batch.vectors().get(RagSmokeIT.QUERY));
            assertEquals("fixture-response-model", batch.evidence().responseModel());
            assertEquals(baseUrl + PATH, batch.evidence().endpoint());
            assertEquals(31, batch.evidence().promptTokens());
            assertEquals(33, batch.evidence().totalTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableProviderDoesNotRetryOrExposeResponseBody() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("private response body " + API_KEY).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var error = assertThrows(IllegalStateException.class,
                    () -> RagSmokeIT.requestEmbeddings(inputs(), baseUrl(server), PATH, API_KEY, MODEL, 3));
            assertTrue(error.getMessage().contains("HTTP 401"));
            assertFalse(error.getMessage().contains(API_KEY));
            assertFalse(error.getMessage().contains("private response body"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEmbeddingResponseWithWrongDimensionBeforeVectorStoreWrite() {
        List<Embedding> embeddings = List.of(new Embedding(new float[]{1, 0}, 0),
                new Embedding(new float[]{0, 1, 0}, 1), new Embedding(new float[]{0, 0, 1}, 2),
                new Embedding(new float[]{1, 1, 0}, 3));
        var error = assertThrows(IllegalStateException.class, () -> RagSmokeIT.validatedVectors(inputs(), embeddings, 3));
        assertTrue(error.getMessage().contains("dimension mismatch"));
    }

    @Test
    void rejectsRetrievedCitationWithValidIdButForgedText() {
        List<Document> documents = RagSmokeIT.sampleDocuments();
        Document original = documents.get(0);
        Document forged = Document.builder().id(original.getId()).metadata(original.getMetadata())
                .text("并非原始文章内容").score(0.99).build();
        var error = assertThrows(IllegalStateException.class, () -> RagSmokeIT.validatedSources(List.of(forged), documents));
        assertTrue(error.getMessage().contains("identity/text"));
    }

    @Test
    void cannotLabelLocalFixtureOrAnotherProviderAsRealEvidence() {
        assertThrows(IllegalStateException.class, () -> RagSmokeIT.verifyRealConfiguration("http://127.0.0.1:9876/v1/embeddings", "text-embedding-v4", 1024));
        assertThrows(IllegalStateException.class, () -> RagSmokeIT.verifyRealConfiguration("https://other.example/v1/embeddings", "text-embedding-v4", 1024));
        assertThrows(IllegalStateException.class, () -> RagSmokeIT.verifyRealConfiguration("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", "text-embedding-v4", 768));
    }

    private static List<String> inputs() {
        var input = new ArrayList<>(RagSmokeIT.sampleDocuments().stream().map(Document::getText).toList());
        input.add(RagSmokeIT.QUERY);
        return input;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
