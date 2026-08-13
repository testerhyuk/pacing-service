package com.settlement.pacing.api.monitoring;

import com.settlement.pacing.api.config.StorageFailureProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageAvailabilityMonitorTest {
    private static final Instant STARTED_AT =
            Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void 반복_장애는_모두_집계하지만_스택_트레이스는_주기별로_제한한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = mock(Clock.class);
        StorageAvailabilityLogSink logSink =
                mock(StorageAvailabilityLogSink.class);
        StorageAvailabilityMonitor monitor = monitor(
                registry,
                clock,
                logSink
        );
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        RuntimeException third = new RuntimeException("third");

        when(clock.instant()).thenReturn(
                STARTED_AT,
                STARTED_AT.plusSeconds(5),
                STARTED_AT.plusSeconds(31)
        );

        monitor.recordFailure(
                StorageType.REDIS,
                StorageOperation.DECISION,
                first
        );
        monitor.recordFailure(
                StorageType.REDIS,
                StorageOperation.DECISION,
                second
        );
        monitor.recordFailure(
                StorageType.REDIS,
                StorageOperation.DECISION,
                third
        );

        verify(logSink).failure(
                StorageType.REDIS,
                StorageOperation.DECISION,
                STARTED_AT,
                1L,
                0L,
                first
        );
        verify(logSink).failure(
                StorageType.REDIS,
                StorageOperation.DECISION,
                STARTED_AT,
                3L,
                1L,
                third
        );
        verify(logSink, times(2)).failure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );

        assertThat(registry.get("pacing.api.storage.failure")
                .tags(
                        "storage", "REDIS",
                        "operation", "DECISION"
                )
                .counter()
                .count()).isEqualTo(3.0);
    }

    @Test
    void 장애_후_첫_성공만_복구로_기록한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = mock(Clock.class);
        StorageAvailabilityLogSink logSink =
                mock(StorageAvailabilityLogSink.class);
        StorageAvailabilityMonitor monitor = monitor(
                registry,
                clock,
                logSink
        );
        RuntimeException exception = new RuntimeException("failure");

        when(clock.instant()).thenReturn(
                STARTED_AT,
                STARTED_AT.plusSeconds(12)
        );

        monitor.recordFailure(
                StorageType.POSTGRESQL,
                StorageOperation.RESERVATION,
                exception
        );
        monitor.recordSuccess(
                StorageType.POSTGRESQL,
                StorageOperation.RESERVATION
        );
        monitor.recordSuccess(
                StorageType.POSTGRESQL,
                StorageOperation.RESERVATION
        );

        verify(logSink).recovery(
                StorageType.POSTGRESQL,
                StorageOperation.RESERVATION,
                STARTED_AT,
                STARTED_AT.plusSeconds(12),
                Duration.ofSeconds(12),
                1L
        );
        verify(logSink, times(1)).recovery(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );

        assertThat(registry.get("pacing.api.storage.recovery")
                .tags(
                        "storage", "POSTGRESQL",
                        "operation", "RESERVATION"
                )
                .counter()
                .count()).isEqualTo(1.0);
    }

    private StorageAvailabilityMonitor monitor(
            SimpleMeterRegistry registry,
            Clock clock,
            StorageAvailabilityLogSink logSink
    ) {
        return new StorageAvailabilityMonitor(
                registry,
                clock,
                new StorageFailureProperties(
                        Duration.ofSeconds(30)
                ),
                new StorageFailureClassifier(),
                logSink
        );
    }
}
