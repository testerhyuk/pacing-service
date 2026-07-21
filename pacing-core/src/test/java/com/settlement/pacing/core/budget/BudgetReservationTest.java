package com.settlement.pacing.core.budget;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetReservationTest {
    private final Instant reservedAt = Instant.parse("2026-07-21T06:00:00Z");

    private final Instant expiresAt = Instant.parse("2026-07-21T06:05:00Z");

    @Test
    void 만료_시각_이전이면_만료되지_않는다() {
        BudgetReservation reservation = createReservation(
                reservedAt,
                expiresAt,
                new Money(1_000)
        );

        Instant now = Instant.parse("2026-07-21T06:04:59Z");

        assertThat(reservation.isExpiredAt(now)).isFalse();
    }

    @Test
    void 만료_시각과_같으면_만료된_것으로_판단한다() {
        BudgetReservation reservation = createReservation(
                reservedAt,
                expiresAt,
                new Money(1_000)
        );

        assertThat(reservation.isExpiredAt(expiresAt)).isTrue();
    }

    @Test
    void 예약_금액이_0이면_생성할_수_없다() {
        assertThatThrownBy(() ->
                createReservation(
                        reservedAt,
                        expiresAt,
                        Money.zero()
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만료_시각이_예약_시각과_같으면_생성할_수_없다() {
        assertThatThrownBy(() ->
                createReservation(
                        reservedAt,
                        reservedAt,
                        new Money(1_000)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만료_시각이_예약_시각보다_이전이면_생성할_수_없다() {
        Instant earlierExpiresAt = reservedAt.minusSeconds(1);

        assertThatThrownBy(() ->
                createReservation(
                        reservedAt,
                        earlierExpiresAt,
                        new Money(1_000)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private BudgetReservation createReservation(
            Instant reservedAt,
            Instant expiresAt,
            Money amount
    ) {
        return new BudgetReservation(
                "reservation-1",
                "campaign-1",
                LocalDate.of(2026, 7, 21),
                amount,
                reservedAt,
                expiresAt
        );
    }
}