package com.settlement.pacing.worker.billing.message;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.core.budget.Money;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record BillingEventMessage(
        String eventId,
        String reservationId,
        BillingEventType eventType,
        long targetAppliedAmount,
        long sequence,
        Instant occurredAt
) {
    public BillingEvent toDomain() {
        return new BillingEvent(
                eventId,
                reservationId,
                eventType,
                new Money(targetAppliedAmount),
                sequence,
                occurredAt == null
                        ? null
                        : occurredAt.truncatedTo(ChronoUnit.MICROS)
        );
    }
}
