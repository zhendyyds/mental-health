package com.flj.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerRuntimeConfigTest {
    private final ApplicationContextRunner container = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ContainerRuntimeConfig.class, PropertyPlaceholderAutoConfiguration.class)
            .withPropertyValues("spring.profiles.active=container",
                    "DB_URL=jdbc:mysql://mysql:3306/fixture", "DB_USERNAME=fixture-app", "DB_PASSWORD=fixture-db-password",
                    "JWT_SECRET=fixture-only-signing-key-at-least-32-bytes", "AI_API_KEY=fictional-chat-key", "AI_CHAT_MODEL=fixture-chat",
                    "FILE_UPLOAD_DIR=/fixture/upload", "RAG_ENABLED=true", "RAG_VERSION=fixture-v1", "RAG_COLLECTION=fixture_collection",
                    "RAG_QDRANT_HOST=qdrant", "RAG_EMBEDDING_API_KEY=fictional-embedding-key");

    @Test
    void containerProfileBindsSeparateUploadDatabaseAndRagSettings() {
        container.run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://mysql:3306/fixture");
            assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("3000");
            assertThat(env.getProperty("file.upload-dir")).isEqualTo("/fixture/upload");
            assertThat(env.getProperty("app.rag.enabled")).isEqualTo("true");
            assertThat(env.getProperty("app.rag.version")).isEqualTo("fixture-v1");
            assertThat(env.getProperty("app.rag.collection-name")).isEqualTo("fixture_collection");
            assertThat(env.getProperty("app.rag.qdrant-host")).isEqualTo("qdrant");
            assertThat(env.getProperty("app.rag.embedding-api-key")).isNotEqualTo(env.getProperty("spring.ai.openai.api-key"));
        });
    }

    @Test
    void containerCannotStartWithShortJwtSecretOrEmptyDatabasePassword() {
        container.withPropertyValues("JWT_SECRET=short-fixture").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("JWT_SECRET must contain at least 32 bytes");
        });
        container.withPropertyValues("DB_PASSWORD=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("DB_PASSWORD is required");
        });
    }
}
