package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;

public record RedisBillingTransition(
        RedisTransitionStatus transitionStatus,
        String eventId,
        String reservationId,
        ReservationStatus reservationStatus,
        Money appliedAmount,
        long reservationVersion,
        Money totalOverageAmount,
        Money dailyOverageAmount
) {
    public RedisBillingTransition {
        if (transitionStatus == null
                || eventId == null
                || eventId.isBlank()
                || reservationId == null
                || reservationId.isBlank()
                || reservationStatus == null
                || appliedAmount == null
                || reservationVersion < 0
                || totalOverageAmount == null
                || dailyOverageAmount == null) {
            throw new IllegalArgumentException(
                    "Redis 과금 전환 결과가 올바르지 않습니다"
            );
        }
    }

    public enum RedisTransitionStatus {
        APPLIED,
        ALREADY_APPLIED
    }
}
