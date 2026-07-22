package com.settlement.pacing.core.budget;

import java.time.Instant;
import java.time.LocalDate;

public record BudgetReservation(
        String reservationId,
        String campaignId,
        LocalDate budgetDate,
        Money amount,
        ReservationStatus status,
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
                || status == null
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

    /**
     * 새로운 예산 예약을 RESERVED 상태로 생성한다.
     */
    public BudgetReservation(
            String reservationId,
            String campaignId,
            LocalDate budgetDate,
            Money amount,
            Instant reservedAt,
            Instant expiresAt
    ) {
        this(
                reservationId,
                campaignId,
                budgetDate,
                amount,
                ReservationStatus.RESERVED,
                reservedAt,
                expiresAt
        );
    }

    public boolean isExpiredAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("현재 시각은 null일 수 없습니다");
        }

        return !now.isBefore(expiresAt);
    }

    /**
     * 예약을 실제 과금이 발생한 상태로 확정한다.
     */
    public BudgetReservation confirm() {
        // 중복 과금 이벤트가 들어오면 상태를 다시 변경하지 않는다.
        if (status == ReservationStatus.CONFIRMED) {
            return this;
        }

        // 취소가 확정된 예약은 다시 확정할 수 없다.
        if (status == ReservationStatus.CANCELLED) {
            throw new IllegalStateException(
                    "취소된 예약은 확정할 수 없습니다"
            );
        }

        /*
         * RESERVED는 정상 과금으로 확정한다.
         * EXPIRED도 지연된 과금 이벤트가 들어올 수 있으므로 확정한다.
         */
        return withStatus(ReservationStatus.CONFIRMED);
    }

    /**
     * 나머지 예약 정보는 유지하고 상태만 변경한다.
     */
    private BudgetReservation withStatus(
            ReservationStatus newStatus
    ) {
        return new BudgetReservation(
                reservationId,
                campaignId,
                budgetDate,
                amount,
                newStatus,
                reservedAt,
                expiresAt
        );
    }

    /**
     * 예약 또는 확정된 과금을 취소한다.
     */
    public BudgetReservation cancel() {
        // 중복 취소 이벤트는 상태를 다시 변경하지 않는다.
        if (status == ReservationStatus.CANCELLED) {
            return this;
        }

        return withStatus(ReservationStatus.CANCELLED);
    }

    /**
     * 만료 시각이 지난 RESERVED 예약을 EXPIRED 상태로 변경한다.
     */
    public BudgetReservation expireAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException(
                    "현재 시각은 null일 수 없습니다"
            );
        }

        /*
         * 이미 확정·취소·만료된 예약은
         * 만료 작업이 다시 실행돼도 상태를 변경하지 않는다.
         */
        if (status != ReservationStatus.RESERVED) {
            return this;
        }

        if (!isExpiredAt(now)) {
            throw new IllegalStateException(
                    "만료 시각 전에는 예약을 만료할 수 없습니다"
            );
        }

        return withStatus(ReservationStatus.EXPIRED);
    }
}
