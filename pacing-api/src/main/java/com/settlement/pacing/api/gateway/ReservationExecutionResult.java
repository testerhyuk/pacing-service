package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.budget.BudgetReservation;

public record ReservationExecutionResult(
        ReservationExecutionStatus status,
        BudgetReservation reservation
) {
    public ReservationExecutionResult {
        if (status == null) throw new IllegalArgumentException("status는 null일 수 없습니다");

        if (status.equals(ReservationExecutionStatus.CREATED)
                || status.equals(ReservationExecutionStatus.ALREADY_EXISTS)) {
            if (reservation == null) {
                throw new IllegalArgumentException("CREATED 또는 ALREADY_EXISTS 상태에서 reservation은 반드시 존재해야 합니다");
            }
        }
    }
}
