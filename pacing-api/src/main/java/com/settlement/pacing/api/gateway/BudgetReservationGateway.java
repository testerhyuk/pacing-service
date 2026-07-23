package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.budget.BudgetReservation;

import java.util.Optional;

public interface BudgetReservationGateway {
    Optional<BudgetReservation> findById(
            String reservationId
    );

    ReservationExecutionResult reserve(
            BudgetReservation reservation
    );
}
