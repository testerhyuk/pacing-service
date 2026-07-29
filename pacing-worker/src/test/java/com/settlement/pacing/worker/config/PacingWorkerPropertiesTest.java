package com.settlement.pacing.worker.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingWorkerPropertiesTest {

    @Test
    void 종료_예약_TTL은_0보다_커야_한다() {
        assertThatThrownBy(() ->
                new PacingWorkerProperties(
                        kafka(),
                        expiration(),
                        reservationRepair(),
                        reconciliation(),
                        Duration.ofDays(30),
                        Duration.ZERO
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("종료 예약 TTL는 0보다 커야 합니다");
    }

    @Test
    void 처리_이벤트_TTL은_0보다_커야_한다() {
        assertThatThrownBy(() ->
                new PacingWorkerProperties(
                        kafka(),
                        expiration(),
                        reservationRepair(),
                        reconciliation(),
                        Duration.ZERO,
                        Duration.ofDays(30)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리 이벤트 TTL는 0보다 커야 합니다");
    }

    @Test
    void Kafka_최대_Backoff는_최초_Backoff보다_짧을_수_없다() {
        assertThatThrownBy(() ->
                new PacingWorkerProperties.Kafka(
                        "billing.events",
                        "pacing-worker",
                        1,
                        1,
                        (short) 1,
                        2,
                        1_000L,
                        2.0,
                        999L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Kafka 최대 재시도 간격은 최초 간격보다 짧을 수 없습니다"
                );
    }

    @Test
    void 예약_만료_실행_주기는_0보다_커야_한다() {
        assertThatThrownBy(() ->
                new PacingWorkerProperties.Expiration(
                        Duration.ZERO,
                        100
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "예약 만료 실행 주기는 0보다 커야 합니다"
                );
    }

    private PacingWorkerProperties.Kafka kafka() {
        return new PacingWorkerProperties.Kafka(
                "billing.events",
                "pacing-worker",
                1,
                1,
                (short) 1,
                2,
                100L,
                2.0,
                1_000L
        );
    }

    private PacingWorkerProperties.Expiration expiration() {
        return new PacingWorkerProperties.Expiration(
                Duration.ofSeconds(1),
                100
        );
    }

    private PacingWorkerProperties.ReservationRepair
    reservationRepair() {
        return new PacingWorkerProperties.ReservationRepair(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                100
        );
    }

    private PacingWorkerProperties.Reconciliation reconciliation() {
        return new PacingWorkerProperties.Reconciliation(
                "0 10 0 * * *",
                ZoneId.of("Asia/Seoul"),
                100
        );
    }
}
