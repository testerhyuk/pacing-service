package com.settlement.pacing.api.config;

import com.settlement.pacing.api.security.ClientPermission;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSecurityPropertiesTest {
    private static final String CURRENT_SECRET =
            "current-secret-key-with-32-bytes-minimum";
    private static final String PREVIOUS_SECRET =
            "previous-secret-key-with-32-bytes-minimum";

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void 유효한_HMAC_설정을_생성한다() {
        HmacSecurityProperties properties = properties(
                new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        PREVIOUS_SECRET,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Set.of(ClientPermission.PACING_DECIDE)
                )
        );

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.findClient("ad-server"))
                .isPresent();
        assertThat(properties.clients()).isUnmodifiable();
    }

    @Test
    void timestamp_허용_오차는_0보다_커야_한다() {
        assertThatThrownBy(() -> new HmacSecurityProperties(
                Duration.ZERO,
                Duration.ofMinutes(2),
                65_536,
                Map.of("ad-server", validClient())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonce_TTL은_0보다_커야_한다() {
        assertThatThrownBy(() -> new HmacSecurityProperties(
                Duration.ofSeconds(60),
                Duration.ZERO,
                65_536,
                Map.of("ad-server", validClient())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 현재_키와_이전_키는_달라야_한다() {
        assertThatThrownBy(
                () -> new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        CURRENT_SECRET,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Set.of(ClientPermission.PACING_DECIDE)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 현재_키가_32바이트보다_짧으면_거절한다() {
        assertThatThrownBy(
                () -> new HmacSecurityProperties.Client(
                        "short-secret",
                        null,
                        null,
                        Set.of(ClientPermission.PACING_DECIDE)
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    void 이전_키가_32바이트보다_짧으면_거절한다() {
        assertThatThrownBy(
                () -> new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        "short-secret",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Set.of(ClientPermission.PACING_DECIDE)
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    void 이전_키가_비어있으면_현재_키만_검증에_사용한다() {
        HmacSecurityProperties.Client client =
                new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        " ",
                        null,
                        Set.of(ClientPermission.PACING_DECIDE)
                );

        assertThat(client.verificationKeys(
                Instant.parse("2026-07-26T00:00:00Z")
        ))
                .containsExactly(CURRENT_SECRET);
    }

    @Test
    void 이전_키는_설정한_유효_종료_시각_전까지만_사용한다() {
        Instant validUntil =
                Instant.parse("2026-08-01T00:00:00Z");
        HmacSecurityProperties.Client client =
                new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        PREVIOUS_SECRET,
                        validUntil,
                        Set.of(ClientPermission.PACING_DECIDE)
                );

        assertThat(client.verificationKeys(
                validUntil.minusNanos(1)
        )).containsExactly(CURRENT_SECRET, PREVIOUS_SECRET);
        assertThat(client.verificationKeys(validUntil))
                .containsExactly(CURRENT_SECRET);
    }

    @Test
    void 이전_키에는_유효_종료_시각이_필요하다() {
        assertThatThrownBy(() ->
                new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        PREVIOUS_SECRET,
                        null,
                        Set.of(ClientPermission.PACING_DECIDE)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 클라이언트가_없으면_설정_검증에_실패한다() {
        HmacSecurityProperties properties =
                new HmacSecurityProperties(
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(2),
                        65_536,
                        Map.of()
                );

        assertThat(validator.validate(properties))
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .equals("clients")
                );
    }

    @Test
    void 권한이_없는_클라이언트는_설정_검증에_실패한다() {
        HmacSecurityProperties properties = properties(
                new HmacSecurityProperties.Client(
                        CURRENT_SECRET,
                        null,
                        null,
                        Set.of()
                )
        );

        assertThat(validator.validate(properties))
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .contains("permissions")
                );
    }

    private HmacSecurityProperties properties(
            HmacSecurityProperties.Client client
    ) {
        return new HmacSecurityProperties(
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                65_536,
                Map.of("ad-server", client)
        );
    }

    private HmacSecurityProperties.Client validClient() {
        return new HmacSecurityProperties.Client(
                CURRENT_SECRET,
                null,
                null,
                Set.of(ClientPermission.PACING_DECIDE)
        );
    }
}
