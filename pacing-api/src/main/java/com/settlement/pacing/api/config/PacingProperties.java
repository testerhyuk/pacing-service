package com.settlement.pacing.api.config;

import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.Rate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

@ConfigurationProperties(prefix = "pacing")
@Validated
public record PacingProperties(
        @NotNull ZoneId businessZoneId,
        @NotNull Duration requestTimeTolerance,
        @NotNull Duration rateUpdateInterval,
        @NotNull @Valid Observation observation,
        @NotNull Duration reservationTtl,
        @Min(0) int stateUpdateMaxRetries,
        @NotNull @Valid InitialRate initialRate,
        @NotNull @Valid Peak peak,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax("1.0")
        double ewmaAlpha
) {
    public PacingProperties {
        validatePositiveDuration(
                requestTimeTolerance,
                "요청 시각 허용 오차"
        );
        validatePositiveDuration(
                rateUpdateInterval,
                "페이싱 비율 갱신 주기"
        );
        validatePositiveDuration(
                reservationTtl,
                "예약 TTL"
        );

        if (observation != null
                && rateUpdateInterval != null) {
            Duration window = observation.window();

            if (window != null
                    && window.compareTo(rateUpdateInterval) < 0) {
                throw new IllegalArgumentException(
                        "관측 구간은 페이싱 갱신 주기보다 짧을 수 없습니다"
                );
            }

            if (window != null
                    && rateUpdateInterval.toMillis() > 0L
                    && window.toMillis()
                    % rateUpdateInterval.toMillis() != 0L) {
                throw new IllegalArgumentException(
                        "관측 구간은 페이싱 갱신 주기의 배수여야 합니다"
                );
            }
        }
    }

    public Rate initialRate(PacingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("페이싱 전략은 null일 수 없습니다");
        }

        return switch (strategy) {
            case EVEN -> new Rate(initialRate.even());
            case PEAK_WEIGHTED -> new Rate(initialRate.peakWeighted());
            case ASAP -> new Rate(initialRate.asap());
        };
    }

    public record InitialRate(
            @DecimalMin("0.0") @DecimalMax("1.0") double even,
            @DecimalMin("0.0") @DecimalMax("1.0") double peakWeighted,
            @DecimalMin("0.0") @DecimalMax("1.0") double asap
    ) {
    }

    public record Observation(
            @NotNull Duration window,
            @Min(1) long minimumPassCount,
            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax("1.0")
            double smoothingFactor,
            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax("1.0")
            double maxRateChange,
            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax("1.0")
            double explorationStep
    ) {
        public Observation {
            validatePositiveDuration(window, "페이싱 관측 구간");
        }
    }

    public record Peak(
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull ZoneId zoneId,
            @DecimalMin(value = "0.0", inclusive = false)
            double normalWeight,
            @DecimalMin(value = "0.0", inclusive = false)
            double peakWeight
    ) {
        public Peak {
            if (startTime != null
                    && endTime != null
                    && !startTime.isBefore(endTime)) {
                throw new IllegalArgumentException(
                        "피크 시작 시각은 종료 시각보다 이전이어야 합니다"
                );
            }

            if (!Double.isFinite(normalWeight)
                    || !Double.isFinite(peakWeight)) {
                throw new IllegalArgumentException(
                        "피크 가중치는 유한한 숫자여야 합니다"
                );
            }

            if (normalWeight > 0.0
                    && peakWeight > 0.0
                    && peakWeight <= normalWeight) {
                throw new IllegalArgumentException(
                        "피크 가중치는 일반 시간대 가중치보다 커야 합니다"
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

        if (duration != null && duration.toMillis() == 0L) {
            throw new IllegalArgumentException(
                    fieldName + "는 1밀리초 이상이어야 합니다"
            );
        }
    }
}
