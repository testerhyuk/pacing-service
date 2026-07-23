package com.settlement.pacing.api.reservation.web;

import com.settlement.pacing.api.reservation.application.BudgetReservationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BudgetReservationRequest(
        @NotBlank @Size(min = 1, max = 100) String reservationId,
        @NotBlank @Size(min = 1, max = 100) String campaignId,
        @Positive long amount
) {
    public BudgetReservationCommand toCommand() {
        return new BudgetReservationCommand(reservationId, campaignId, amount);
    }
}
