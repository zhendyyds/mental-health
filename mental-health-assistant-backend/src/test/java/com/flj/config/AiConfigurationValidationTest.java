package com.flj.config;

import com.flj.AiService.governance.AiProtectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationValidationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void emptyJwtSecretCannotSilentlyBecomeTheSigningKey() {
        context.withPropertyValues("jwt.secret= ").run(result -> assertThat(result).hasFailed());
        context.run(result -> assertThat(result).hasFailed());
    }

    @Test
    void explicitSecretAndDefaultAiLimitsBind() {
        context.withPropertyValues("jwt.secret=fixture-only-signing-key").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(AiProtectionProperties.class).getMaxConcurrent()).isEqualTo(4);
            assertThat(result.getBean(JwtConfig.class).getSecret()).isEqualTo("fixture-only-signing-key");
        });
    }

    @Test
    void invalidCallBudgetsFailAtConfigurationBinding() {
        context.withPropertyValues("jwt.secret=fixture-only-signing-key", "app.ai.protection.max-concurrent=0")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("jwt.secret=fixture-only-signing-key", "app.ai.protection.max-input-units=10",
                        "app.ai.protection.reserved-input-units=10")
                .run(result -> assertThat(result).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtConfig.class, AiProtectionProperties.class})
    static class PropertiesConfiguration { }
}
