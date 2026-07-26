package com.settlement.pacing.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@ConfigurationProperties(prefix = "pacing.infrastructure.rate-limit")
@Validated
public record RateLimitProperties(
        /* 요청이 없는 Rate Limit 상태를 Redis에서 제거할 시간 */
        @NotNull
        Duration idleTtl,

        /* clientId별 Rate Limit 설정 */
        @NotEmpty
        Map<@NotBlank String, @Valid ClientLimit> clients
) {
    public RateLimitProperties {
        if (idleTtl != null
                && (idleTtl.isZero() || idleTtl.isNegative())) {
            throw new IllegalArgumentException(
                    "Rate Limit 유휴 TTL은 0보다 커야 합니다"
            );
        }

        if (clients != null) {
            clients = Map.copyOf(clients);
        }
    }

    public Optional<ClientLimit> findClient(
            String clientId
    ) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(clients.get(clientId));
    }

    public record ClientLimit(
            /* 한 번에 보관할 수 있는 최대 요청 토큰 수 */
            @Min(1)
            long capacity,

            /* 1초마다 다시 충전할 토큰 수 */
            double refillTokensPerSecond
    ) {
        public ClientLimit {
            if (!Double.isFinite(refillTokensPerSecond)
                    || refillTokensPerSecond <= 0.0) {
                throw new IllegalArgumentException(
                        "초당 토큰 충전량은 0보다 큰 유한한 숫자여야 합니다"
                );
            }
        }
    }
}