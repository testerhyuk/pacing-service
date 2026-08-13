package com.settlement.pacing.worker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "pacing.worker")
public record PacingWorkerProperties(
        @NotNull @Valid Kafka kafka,
        @NotNull @Valid Expiration expiration,
        @NotNull @Valid ReservationRepair reservationRepair,
        @NotNull @Valid Reconciliation reconciliation,
        @NotNull Duration processedEventTtl,
        @NotNull Duration terminalReservationTtl
) {
    public PacingWorkerProperties {
        validatePositive(processedEventTtl, "처리 이벤트 TTL");
        validatePositive(
                terminalReservationTtl,
                "종료 예약 TTL"
        );
    }

    public record Kafka(
            @NotBlank String billingTopic,
            @NotBlank String consumerGroup,
            @Min(1) int concurrency,
            @Min(1) int partitions,
            @Min(1) short replicationFactor,
            @Min(2) int retryAttempts,
            @Min(1) long initialBackoffMillis,
            @DecimalMin(value = "1.0")
            double backoffMultiplier,
            @Min(1) long maxBackoffMillis
    ) {
        public Kafka {
            if (!Double.isFinite(backoffMultiplier)) {
                throw new IllegalArgumentException(
                        "Kafka 재시도 배수는 유한한 숫자여야 합니다"
                );
            }

            if (maxBackoffMillis < initialBackoffMillis) {
                throw new IllegalArgumentException(
                        "Kafka 최대 재시도 간격은 최초 간격보다 짧을 수 없습니다"
                );
            }
        }
    }

    public record Expiration(
            @NotNull Duration fixedDelay,
            @Min(1) int batchSize
    ) {
        public Expiration {
            validatePositive(fixedDelay, "예약 만료 실행 주기");
        }
    }

    public record ReservationRepair(
            @NotNull Duration fixedDelay,
            @NotNull Duration gracePeriod,
            @Min(1) int batchSize
    ) {
        public ReservationRepair {
            validatePositive(
                    fixedDelay,
                    "예약 영속화 복구 실행 주기"
            );

            validatePositive(
                    gracePeriod,
                    "예약 영속화 복구 유예 시간"
            );
        }
    }

    public record Reconciliation(
            @NotBlank String cron,
            @NotNull ZoneId zoneId,
            @Min(1) int batchSize,
            @Min(1) int maxRepairAttempts
    ) {
    }

    private static void validatePositive(
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
