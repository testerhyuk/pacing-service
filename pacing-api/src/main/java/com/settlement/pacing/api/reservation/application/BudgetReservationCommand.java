package com.settlement.pacing.api.reservation.application;

import com.settlement.pacing.api.error.InvalidRequestException;

public record BudgetReservationCommand(
        String reservationId,
        String campaignId,
        long amount
) {
    public BudgetReservationCommand {
        validateIdentifier(reservationId, "reservationId");
        validateIdentifier(campaignId, "campaignId");

        if (amount <= 0L) {
            throw new InvalidRequestException(
                    "amount는 0보다 커야 합니다"
            );
        }
    }

    private static void validateIdentifier(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(
                    fieldName + "는 null이거나 비어있을 수 없습니다"
            );
        }

        if (value.length() > 100) {
            throw new InvalidRequestException(
                    fieldName + "는 100자를 초과할 수 없습니다"
            );
        }
    }
}
