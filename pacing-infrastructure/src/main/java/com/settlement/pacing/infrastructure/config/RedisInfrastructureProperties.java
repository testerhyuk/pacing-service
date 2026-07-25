package com.settlement.pacing.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "pacing.infrastructure.redis")
@Validated
public record RedisInfrastructureProperties(
        /* Redis Key 앞에 공통으로 붙일 문자열 */
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9_-]+")
        String keyPrefix,

        /* 캠페인 정책 Cache 유지 시간 */
        @NotNull
        Duration campaignCacheTtl,

        /* 예산 복구 작업의 Lock 유지 시간 */
        @NotNull
        Duration recoveryLockTtl,

        /* 다른 요청의 예산 복구 완료를 기다리는 최대 시간 */
        @NotNull
        Duration recoveryWaitTimeout,

        /* 예산 복구 완료 여부를 다시 확인하는 간격 */
        @NotNull
        Duration recoveryRetryInterval
) {
    public RedisInfrastructureProperties {
        validatePositive(campaignCacheTtl, "캠페인 Cache TTL");
        validatePositive(recoveryLockTtl, "예산 복구 Lock TTL");
        validatePositive(recoveryWaitTimeout, "예산 복구 대기 시간");
        validatePositive(recoveryRetryInterval, "예산 복구 재확인 간격");

        if (recoveryRetryInterval != null
                && recoveryWaitTimeout != null
                && recoveryRetryInterval.compareTo(
                recoveryWaitTimeout
        ) >= 0) {
            throw new IllegalArgumentException(
                    "예산 복구 재확인 간격은 최대 대기 시간보다 짧아야 합니다"
            );
        }
    }

    private static void validatePositive(
            Duration duration,
            String fieldName
    ) {
        if (duration != null
                && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(
                    fieldName + "은 0보다 커야 합니다"
            );
        }
    }
}