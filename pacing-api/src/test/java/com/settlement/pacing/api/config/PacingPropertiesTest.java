package com.settlement.pacing.api.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingPropertiesTest {
    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
    }

    @Test
    void 정상_설정은_검증을_통과한다() {
        Set<ConstraintViolation<PacingProperties>> violations =
                validator.validate(validProperties());

        assertThat(violations).isEmpty();
    }

    @Test
    void 음수_재시도_횟수를_거절한다() {
        PacingProperties properties = new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                validObservation(),
                Duration.ofMinutes(5),
                -1,
                validInitialRate(),
                validPeak(),
                0.5
        );

        assertThat(validator.validate(properties))
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .equals("stateUpdateMaxRetries")
                );
    }

    @Test
    void 영_이하의_예약_TTL을_거절한다() {
        assertThatThrownBy(() -> new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                validObservation(),
                Duration.ZERO,
                3,
                validInitialRate(),
                validPeak(),
                0.5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 범위를_벗어난_초기_비율을_거절한다() {
        PacingProperties properties = new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                validObservation(),
                Duration.ofMinutes(5),
                3,
                new PacingProperties.InitialRate(
                        -0.1,
                        0.5,
                        1.1
                ),
                validPeak(),
                0.5
        );

        assertThat(validator.validate(properties))
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .startsWith("initialRate.")
                );
    }

    @Test
    void null_업무_시간대를_거절한다() {
        PacingProperties properties = new PacingProperties(
                null,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                validObservation(),
                Duration.ofMinutes(5),
                3,
                validInitialRate(),
                validPeak(),
                0.5
        );

        assertThat(validator.validate(properties))
                .anyMatch(violation ->
                        violation.getPropertyPath()
                                .toString()
                                .equals("businessZoneId")
                );
    }

    @Test
    void 피크_시작_시각이_종료_시각보다_빠르지_않으면_거절한다() {
        assertThatThrownBy(() -> new PacingProperties.Peak(
                LocalTime.of(23, 0),
                LocalTime.of(18, 0),
                ZoneId.of("Asia/Seoul"),
                0.5,
                1.5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 피크_가중치가_일반_가중치보다_크지_않으면_거절한다() {
        assertThatThrownBy(() -> new PacingProperties.Peak(
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                ZoneId.of("Asia/Seoul"),
                1.5,
                1.5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 관측_구간이_갱신_주기보다_짧으면_거절한다() {
        assertThatThrownBy(() -> new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                new PacingProperties.Observation(
                        Duration.ofSeconds(5),
                        20L,
                        0.5,
                        0.2,
                        0.1
                ),
                Duration.ofMinutes(5),
                3,
                validInitialRate(),
                validPeak(),
                0.5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 관측_구간이_갱신_주기의_배수가_아니면_거절한다() {
        assertThatThrownBy(() -> new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                new PacingProperties.Observation(
                        Duration.ofSeconds(25),
                        20L,
                        0.5,
                        0.2,
                        0.1
                ),
                Duration.ofMinutes(5),
                3,
                validInitialRate(),
                validPeak(),
                0.5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private PacingProperties validProperties() {
        return new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                validObservation(),
                Duration.ofMinutes(5),
                3,
                validInitialRate(),
                validPeak(),
                0.5
        );
    }

    private PacingProperties.InitialRate validInitialRate() {
        return new PacingProperties.InitialRate(
                0.1,
                0.1,
                1.0
        );
    }

    private PacingProperties.Observation validObservation() {
        return new PacingProperties.Observation(
                Duration.ofMinutes(1),
                20L,
                0.5,
                0.2,
                0.1
        );
    }

    private PacingProperties.Peak validPeak() {
        return new PacingProperties.Peak(
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                ZoneId.of("Asia/Seoul"),
                0.5,
                1.5
        );
    }
}
