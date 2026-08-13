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
        Money targetAppliedAmount,
        long sequence,
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
                || targetAppliedAmount == null
                || occurredAt == null) {
            throw new IllegalArgumentException(
                    "과금 이벤트 값은 null일 수 없습니다"
            );
        }

        if (sequence <= 0) {
            throw new IllegalArgumentException(
                    "과금 이벤트 순번은 0보다 커야 합니다"
            );
        }

        if (eventType == BillingEventType.CHARGED
                && targetAppliedAmount.isZero()) {
            throw new IllegalArgumentException(
                    "과금 확정 후 적용 금액은 0보다 커야 합니다"
            );
        }
    }
}
