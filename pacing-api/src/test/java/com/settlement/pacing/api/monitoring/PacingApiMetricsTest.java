package com.settlement.pacing.api.monitoring;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.PacingObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PacingApiMetricsTest {

    @Test
    void 페이싱_갱신에_사용한_관측값과_적용_비율을_기록한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PacingProperties pacingProperties = new PacingProperties(
                ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                new PacingProperties.Observation(
                        Duration.ofMinutes(1),
                        20L,
                        0.5,
                        0.2,
                        0.1
                ),
                Duration.ofMinutes(5),
                3,
                new PacingProperties.InitialRate(
                        0.1,
                        0.1,
                        1.0
                ),
                new PacingProperties.Peak(
                        LocalTime.of(18, 0),
                        LocalTime.of(23, 0),
                        ZoneId.of("Asia/Seoul"),
                        0.5,
                        1.5
                ),
                0.5
        );
        PacingApiMetrics metrics = new PacingApiMetrics(registry, pacingProperties);
        PacingObservation observation = new PacingObservation(
                java.util.List.of(
                        interval(150L, 30L, 15L, 1_500L),
                        interval(150L, 30L, 15L, 1_500L),
                        interval(150L, 30L, 15L, 1_500L),
                        interval(150L, 30L, 15L, 1_500L),
                        interval(150L, 30L, 15L, 1_500L),
                        interval(150L, 30L, 15L, 1_500L)
                )
        );

        metrics.recordPacingRateUpdate(
                PacingStrategy.EVEN,
                observation,
                0.15,
                Duration.ofSeconds(10)
        );

        assertThat(gauge(
                registry,
                "pacing.api.rate_update.pacing_rate"
        )).isEqualTo(0.15);
        assertThat(gauge(
                registry,
                "pacing.api.rate_update.decision_rate"
        )).isEqualTo(15.0);
        assertThat(gauge(
                registry,
                "pacing.api.rate_update.pass_rate"
        )).isEqualTo(0.2);
        assertThat(gauge(
                registry,
                "pacing.api.rate_update.interval_count"
        )).isEqualTo(6.0);
        assertThat(gauge(
                registry,
                "pacing.api.rate_update.reserved_amount_per_interval"
        )).isEqualTo(1_500.0);
        assertThat(gauge(
                registry,
                "pacing.api.rate_update.full_pass_amount_per_interval"
        )).isEqualTo(7_500.0);
        assertThat(registry.get("pacing.api.rate_update")
                .tag("strategy", "EVEN")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private PacingObservation.Interval interval(
            long decisionCount,
            long passCount,
            long reservationCount,
            long reservedAmount
    ) {
        return new PacingObservation.Interval(
                decisionCount,
                passCount,
                reservationCount,
                new Money(reservedAmount)
        );
    }

    private double gauge(
            SimpleMeterRegistry registry,
            String name
    ) {
        return registry.get(name)
                .tag("strategy", "EVEN")
                .gauge()
                .value();
    }
}
