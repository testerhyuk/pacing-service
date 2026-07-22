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
    void 새로운_예약은_RESERVED_상태로_생성된다() {
        BudgetReservation reservation = createReservation(
                reservedAt,
                expiresAt,
                new Money(1_000)
        );

        assertThat(reservation.status())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void RESERVED_예약을_CONFIRMED_상태로_확정한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.RESERVED);

        BudgetReservation confirmed = reservation.confirm();

        assertThat(confirmed.status())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void 만료된_예약도_지연된_과금이_발생하면_CONFIRMED_상태로_확정한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.EXPIRED);

        BudgetReservation confirmed = reservation.confirm();

        assertThat(confirmed.status())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void 이미_CONFIRMED인_예약을_다시_확정하면_기존_예약을_반환한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.CONFIRMED);

        BudgetReservation confirmed = reservation.confirm();

        assertThat(confirmed).isSameAs(reservation);
    }

    @Test
    void 취소된_예약은_확정할_수_없다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.CANCELLED);

        assertThatThrownBy(reservation::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("취소된 예약은 확정할 수 없습니다");
    }

    @Test
    void RESERVED_예약을_CANCELLED_상태로_취소한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.RESERVED);

        BudgetReservation cancelled = reservation.cancel();

        assertThat(cancelled.status())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void CONFIRMED_예약을_CANCELLED_상태로_취소한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.CONFIRMED);

        BudgetReservation cancelled = reservation.cancel();

        assertThat(cancelled.status())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void EXPIRED_예약을_CANCELLED_상태로_취소한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.EXPIRED);

        BudgetReservation cancelled = reservation.cancel();

        assertThat(cancelled.status())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void 이미_CANCELLED인_예약을_다시_취소하면_기존_예약을_반환한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.CANCELLED);

        BudgetReservation cancelled = reservation.cancel();

        assertThat(cancelled).isSameAs(reservation);
    }

    @Test
    void 만료_시각_전에는_RESERVED_예약을_만료할_수_없다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.RESERVED);

        Instant now = expiresAt.minusSeconds(1);

        assertThatThrownBy(() -> reservation.expireAt(now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("만료 시각 전에는 예약을 만료할 수 없습니다");
    }

    @Test
    void 만료_시각에_RESERVED_예약을_EXPIRED_상태로_변경한다() {
        BudgetReservation reservation =
                createReservation(ReservationStatus.RESERVED);

        BudgetReservation expired = reservation.expireAt(expiresAt);

        assertThat(expired.status())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void RESERVED가_아닌_예약은_만료_작업으로_상태가_변경되지_않는다() {
        BudgetReservation confirmed =
                createReservation(ReservationStatus.CONFIRMED);
        BudgetReservation cancelled =
                createReservation(ReservationStatus.CANCELLED);
        BudgetReservation expired =
                createReservation(ReservationStatus.EXPIRED);

        assertThat(confirmed.expireAt(expiresAt)).isSameAs(confirmed);
        assertThat(cancelled.expireAt(expiresAt)).isSameAs(cancelled);
        assertThat(expired.expireAt(expiresAt)).isSameAs(expired);
    }

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

    private BudgetReservation createReservation(
            ReservationStatus status
    ) {
        return new BudgetReservation(
                "reservation-1",
                "campaign-1",
                LocalDate.of(2026, 7, 21),
                new Money(1_000),
                status,
                reservedAt,
                expiresAt
        );
    }
}
