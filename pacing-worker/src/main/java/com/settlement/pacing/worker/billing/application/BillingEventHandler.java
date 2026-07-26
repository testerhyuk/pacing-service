package com.settlement.pacing.worker.billing.application;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class BillingEventHandler {
    private final BillingEventProcessingGateway gateway;
    private final PacingWorkerMetrics metrics;

    public BillingEventHandler(
            BillingEventProcessingGateway gateway,
            PacingWorkerMetrics metrics
    ) {
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public BillingEventProcessingResult handle(
            BillingEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "과금 이벤트는 null일 수 없습니다"
            );
        }

        Timer.Sample sample = metrics.startTimer();

        try {
            BillingEventProcessingResult result =
                    gateway.process(event);
            metrics.recordBilling(
                    sample,
                    event.eventType(),
                    result
            );
            return result;
        } catch (RuntimeException exception) {
            metrics.recordBillingFailure(sample, exception);
            throw exception;
        }
    }

    public void markDeadLetter(
            BillingEventMessageView message,
            String reason
    ) {
        metrics.recordDeadLetter(message.eventType());

        if (message.eventId() != null
                && !message.eventId().isBlank()) {
            gateway.markDeadLetter(
                    message.eventId(),
                    reason
            );
        }
    }

    public record BillingEventMessageView(
            String eventId,
            String eventType
    ) {
    }
}
