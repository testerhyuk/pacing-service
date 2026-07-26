package com.settlement.pacing.api.config;

import com.settlement.pacing.api.security.ClientPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ConfigurationProperties(prefix = "pacing.security.hmac")
@Validated
public record HmacSecurityProperties(
        @NotNull Duration timestampTolerance,
        @NotNull Duration nonceTtl,
        @Positive @Max(10_485_760)
        int maxRequestBodyBytes,
        @NotEmpty
        Map<@NotBlank String, @Valid Client> clients
) {
    public HmacSecurityProperties {
        validatePositiveDuration(
                timestampTolerance,
                "timestamp 허용 오차"
        );
        validatePositiveDuration(
                nonceTtl,
                "nonce TTL"
        );

        if (clients != null) {
            clients = Map.copyOf(clients);
        }
    }

    public Optional<Client> findClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(clients.get(clientId));
    }

    public record Client(
            @NotBlank String currentSecretKey,
            String previousSecretKey,
            Instant previousSecretValidUntil,
            @NotEmpty Set<@NotNull ClientPermission> permissions
    ) {
        public Client {
            validateSecretKey(
                    currentSecretKey,
                    "현재 secretKey"
            );

            if (previousSecretKey != null
                    && previousSecretKey.isBlank()) {
                previousSecretKey = null;
            }

            if (previousSecretKey != null) {
                validateSecretKey(
                        previousSecretKey,
                        "이전 secretKey"
                );

                if (previousSecretValidUntil == null) {
                    throw new IllegalArgumentException(
                            "이전 secretKey의 유효 종료 시각이 필요합니다"
                    );
                }
            } else if (previousSecretValidUntil != null) {
                throw new IllegalArgumentException(
                        "이전 secretKey 없이 유효 종료 시각만 설정할 수 없습니다"
                );
            }

            if (currentSecretKey != null
                    && currentSecretKey.equals(previousSecretKey)) {
                throw new IllegalArgumentException(
                        "현재 secretKey와 이전 secretKey는 달라야 합니다"
                );
            }

            if (permissions != null) {
                permissions = Set.copyOf(permissions);
            }
        }

        public List<String> verificationKeys(Instant now) {
            if (now == null) {
                throw new IllegalArgumentException(
                        "현재 시각은 null일 수 없습니다"
                );
            }

            if (previousSecretKey == null
                    || !now.isBefore(previousSecretValidUntil)) {
                return List.of(currentSecretKey);
            }

            return List.of(
                    currentSecretKey,
                    previousSecretKey
            );
        }

        public boolean hasPermission(
                ClientPermission permission
        ) {
            return permission != null
                    && permissions.contains(permission);
        }

        private static void validateSecretKey(
                String secretKey,
                String fieldName
        ) {
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException(
                        fieldName + "는 null이거나 비어있을 수 없습니다"
                );
            }

            if (secretKey.getBytes(StandardCharsets.UTF_8).length
                    < 32) {
                throw new IllegalArgumentException(
                        fieldName + "는 UTF-8 기준 32바이트 이상이어야 합니다"
                );
            }
        }
    }

    private static void validatePositiveDuration(
            Duration duration,
            String fieldName
    ) {
        if (duration != null
                && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(
                    fieldName + "는 0보다 커야 합니다"
            );
        }
    }
}
