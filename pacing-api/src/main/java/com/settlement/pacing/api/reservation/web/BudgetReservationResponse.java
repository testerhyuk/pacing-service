package com.settlement.pacing.api.reservation.web;

import com.settlement.pacing.api.reservation.application.BudgetReservationResult;
import com.settlement.pacing.core.budget.ReservationStatus;

import java.time.Instant;
import java.time.LocalDate;

public record BudgetReservationResponse(
        String reservationId,
        String campaignId,
        LocalDate budgetDate,
        long amount,
        ReservationStatus status,
        Instant reservedAt,
        Instant expiresAt,
        boolean created
) {
    public static BudgetReservationResponse from(BudgetReservationResult result) {
        return new BudgetReservationResponse(
                result.reservation().reservationId(),
                result.reservation().campaignId(),
                result.reservation().budgetDate(),
                result.reservation().amount().amount(),
                result.reservation().status(),
                result.reservation().reservedAt(),
                result.reservation().expiresAt(),
                result.created()
        );
    }
}
