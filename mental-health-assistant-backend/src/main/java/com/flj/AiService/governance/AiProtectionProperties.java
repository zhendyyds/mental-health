package com.flj.AiService.governance;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Single-instance model admission and size limits. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.protection")
public class AiProtectionProperties {
    @Min(1) private int maxConcurrent = 4;
    @Min(1) private int userRequestsPerMinute = 6;
    @Min(1) private int userDailyCalls = 100;
    @Min(1) private int globalDailyCalls = 1000;
    @Min(1) private int maxTrackedUsers = 10000;
    @Min(1) private int maxInputUnits = 32768;
    @Min(0) private int reservedInputUnits = 1024;
    @Min(1) private int maxOutputTokens = 1024;
    @Min(1) private int maxReplyBytes = 65536;
    @Min(1) private int maxReplyFragments = 4096;

    @AssertTrue(message = "reserved-input-units must be smaller than max-input-units")
    public boolean isInputBudgetValid() {
        return reservedInputUnits < maxInputUnits;
    }
}
