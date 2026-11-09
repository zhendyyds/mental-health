package com.flj.config;


import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix="jwt")
public class JwtConfig {
    @NotBlank(message = "JWT secret must be provided through JWT_SECRET or local configuration")
    private String secret;
    private long expiration;
    private long refreshExpiration;
    private String header;
    private String tokenPrefix;
}
