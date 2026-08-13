package com.settlement.pacing.worker.billing.application;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingEventHandlerTest {
    private BillingEventProcessingGateway gateway;
    private PacingWorkerMetrics metrics;
    private BillingEventHandler handler;
    private Timer.Sample sample;

    @BeforeEach
    void setUp() {
        gateway = mock(BillingEventProcessingGateway.class);
        metrics = mock(PacingWorkerMetrics.class);
        sample = mock(Timer.Sample.class);
        when(metrics.startTimer()).thenReturn(sample);
        handler = new BillingEventHandler(gateway, metrics);
    }

    @Test
    void 과금_처리_결과와_성공_메트릭을_반환한다() {
        BillingEvent event = event();
        BillingEventProcessingResult expected =
                new BillingEventProcessingResult(
                        BillingEventProcessingStatus.APPLIED,
                        event.eventId(),
                        event.reservationId(),
                        ReservationStatus.CONFIRMED,
                        new Money(900L),
                        Money.zero(),
                        Money.zero()
                );
        when(gateway.process(event)).thenReturn(expected);

        BillingEventProcessingResult actual =
                handler.handle(event);

        assertThat(actual).isEqualTo(expected);
        verify(metrics).recordBilling(
                sample,
                BillingEventType.CHARGED,
                expected
        );
    }

    @Test
    void 처리_실패를_기록하고_같은_예외를_다시_던진다() {
        BillingEvent event = event();
        RetryableBillingEventException exception =
                new RetryableBillingEventException("retry");
        when(gateway.process(event)).thenThrow(exception);

        assertThatThrownBy(() -> handler.handle(event))
                .isSameAs(exception);

        verify(metrics).recordBillingFailure(sample, exception);
    }

    private BillingEvent event() {
        return new BillingEvent(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                new Money(900L),
                1L,
                Instant.parse("2026-07-26T01:00:00Z")
        );
    }
}
