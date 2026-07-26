package com.settlement.pacing.worker.billing.application;

import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;

public record BillingEventProcessingResult(
        BillingEventProcessingStatus status,
        String eventId,
        String reservationId,
        ReservationStatus reservationStatus,
        Money appliedAmount,
        Money totalOverageAmount,
        Money dailyOverageAmount
) {
    public BillingEventProcessingResult {
        if (status == null
                || eventId == null
                || eventId.isBlank()
                || reservationId == null
                || reservationId.isBlank()
                || reservationStatus == null
                || appliedAmount == null
                || totalOverageAmount == null
                || dailyOverageAmount == null) {
            throw new IllegalArgumentException(
                    "과금 처리 결과 값은 null이거나 비어있을 수 없습니다"
            );
        }
    }

    public boolean applied() {
        return status == BillingEventProcessingStatus.APPLIED;
    }

    public boolean overBudget() {
        return !totalOverageAmount.isZero()
                || !dailyOverageAmount.isZero();
    }
}
