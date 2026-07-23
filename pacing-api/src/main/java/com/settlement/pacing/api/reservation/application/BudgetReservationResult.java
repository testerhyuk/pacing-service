package com.settlement.pacing.api.reservation.application;

import com.settlement.pacing.core.budget.BudgetReservation;

public record BudgetReservationResult(
        BudgetReservation reservation,
        boolean created
) {
    public BudgetReservationResult {
        if (reservation == null) throw new IllegalArgumentException("예산 예약은 null일 수 없습니다");
    }

    /**
     * 새로운 예약이 생성된 경우 사용한다.
     */
    public static BudgetReservationResult created(BudgetReservation reservation) {
        return new BudgetReservationResult(reservation, true) ;
    }

    /**
     * 동일한 reservationId로 이미 생성된 예약을 반환할 때 사용한다.
     */
    public static BudgetReservationResult existing(BudgetReservation reservation) {
        return  new BudgetReservationResult(reservation, false) ;
    }
}
