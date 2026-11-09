package com.flj.rag;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class QdrantStartupAvailabilityTest {
    @Test
    void optionalQdrantNeverBlocksStartupAndRequestToSilentServerTimesOut() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            silent.setSoTimeout(150);
            RagIndexSettings settings = new RagIndexSettings();
            settings.setEnabled(true);
            settings.setQdrantHost("127.0.0.1");
            settings.setQdrantPort(silent.getLocalPort());
            settings.setQdrantTimeoutMs(200);
            IndexConfig config = new IndexConfig("fixture", "openai-compatible", "https://example.invalid/embeddings",
                    "fixture-model", 2, "fixture_collection", "extract-v1", "chunk-v1", 600, 100);

            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                try (var gateway = new QdrantRagVectorGateway(settings)) {
                    assertThrows(SocketTimeoutException.class, silent::accept, "No network call belongs in the startup path");
                    RagIndexException error = assertThrows(RagIndexException.class,
                            () -> gateway.search(config, new float[]{1, 0}, 1, 0));
                    assertEquals("504", error.getCode());
                }
            });
        }
    }
}
