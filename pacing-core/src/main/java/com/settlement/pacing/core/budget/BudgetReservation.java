package com.settlement.pacing.core.budget;

import java.time.Instant;
import java.time.LocalDate;

public record BudgetReservation(
        String reservationId,
        String campaignId,
        LocalDate budgetDate,
        Money amount,
        Instant reservedAt,
        Instant expiresAt
) {
    public BudgetReservation {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("예약 ID는 null이거나 비어있을 수 없습니다");
        }

        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("캠페인 ID는 null이거나 비어있을 수 없습니다");
        }

        if (budgetDate == null
                || amount == null
                || reservedAt == null
                || expiresAt == null) {
            throw new IllegalArgumentException("예산 예약 값은 null일 수 없습니다");
        }

        if (amount.amount() == 0L) {
            throw new IllegalArgumentException("예약 금액은 0보다 커야 합니다");
        }

        if (!reservedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("예약 만료 시각은 예약 시각보다 이후여야 합니다");
        }
    }

    public boolean isExpiredAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("현재 시각은 null일 수 없습니다");
        }

        return !now.isBefore(expiresAt);
    }
}
