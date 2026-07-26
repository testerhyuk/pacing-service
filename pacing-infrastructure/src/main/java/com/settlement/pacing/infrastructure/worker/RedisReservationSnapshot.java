package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;

public record RedisReservationSnapshot(
        BudgetReservation reservation,
        Money appliedAmount,
        long version
) {
    public RedisReservationSnapshot {
        if (reservation == null
                || appliedAmount == null
                || version < 0) {
            throw new IllegalArgumentException(
                    "Redis 예약 상태가 올바르지 않습니다"
            );
        }
    }
}
