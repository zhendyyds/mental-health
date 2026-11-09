package com.flj.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@Profile("container")
class ContainerRuntimeConfig {
    ContainerRuntimeConfig(@Value("${DB_URL}") String jdbcUrl,
                           @Value("${DB_USERNAME}") String databaseUser,
                           @Value("${DB_PASSWORD}") String databasePassword,
                           @Value("${JWT_SECRET}") String jwtSecret,
                           @Value("${AI_API_KEY}") String chatApiKey,
                           @Value("${AI_CHAT_MODEL}") String chatModel) {
        Assert.isTrue(jdbcUrl.startsWith("jdbc:mysql://"), "DB_URL must select a MySQL database");
        Assert.hasText(databaseUser, "DB_USERNAME is required");
        Assert.hasText(databasePassword, "DB_PASSWORD is required");
        Assert.isTrue(!jwtSecret.isBlank() && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32,
                "JWT_SECRET must contain at least 32 bytes");
        Assert.hasText(chatApiKey, "AI_API_KEY is required");
        Assert.hasText(chatModel, "AI_CHAT_MODEL is required");
    }
}
