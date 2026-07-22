package com.settlement.pacing.core.billing;

import com.settlement.pacing.core.budget.Money;

import java.time.Instant;

/**
 * 외부 과금 시스템에서 전달된 과금 결과다.
 */
public record BillingEvent(
        String eventId,
        String reservationId,
        BillingEventType eventType,
        Money actualAmount,
        Instant occurredAt
) {
    public BillingEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException(
                    "eventId는 null이거나 비어있을 수 없습니다"
            );
        }

        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException(
                    "reservationId는 null이거나 비어있을 수 없습니다"
            );
        }

        if (eventType == null
                || actualAmount == null
                || occurredAt == null) {
            throw new IllegalArgumentException(
                    "과금 이벤트 값은 null일 수 없습니다"
            );
        }

        if (actualAmount.isZero()) {
            throw new IllegalArgumentException(
                    "과금 이벤트 금액은 0보다 커야 합니다"
            );
        }
    }
}