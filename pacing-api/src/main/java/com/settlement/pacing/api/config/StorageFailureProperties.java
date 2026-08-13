package com.settlement.pacing.api.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "pacing.storage-failure")
public record StorageFailureProperties(
        @NotNull Duration logInterval
) {
    public StorageFailureProperties {
        if (logInterval != null
                && (logInterval.isZero()
                || logInterval.isNegative())) {
            throw new IllegalArgumentException(
                    "저장소 장애 로그 주기는 0보다 커야 합니다"
            );
        }
    }
}
