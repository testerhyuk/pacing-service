package com.settlement.pacing.core.billing;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingEventProcessorTest {
    private final BillingEventProcessor processor =
            new BillingEventProcessor();

    private final LocalDate budgetDate =
            LocalDate.of(2026, 7, 21);

    private final Instant reservedAt =
            Instant.parse("2026-07-21T06:00:00Z");

    private final Instant occurredAt =
            Instant.parse("2026-07-21T06:01:00Z");

    @Test
    void RESERVED_예약의_과금을_확정한다() {
        BudgetState budgetState = reservedBudgetState();
        BudgetReservation reservation =
                reservation(ReservationStatus.RESERVED);
        BillingEvent event =
                event(BillingEventType.CHARGED, new Money(900));

        BillingResult result = processor.process(
                budgetState,
                reservation,
                event,
                Money.zero()
        );

        assertThat(result.reservation().status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.budgetState().totalReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(result.budgetState().dailyReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(20_900));
        assertThat(result.budgetState().dailySpentAmount())
                .isEqualTo(new Money(5_900));
        assertThat(result.appliedAmount())
                .isEqualTo(new Money(900));
    }

    @Test
    void EXPIRED_예약의_지연_과금은_소진액만_증가시킨다() {
        BudgetState budgetState = budgetState(
                new Money(20_000),
                Money.zero(),
                new Money(5_000),
                Money.zero()
        );
        BudgetReservation reservation =
                reservation(ReservationStatus.EXPIRED);
        BillingEvent event =
                event(BillingEventType.CHARGED, new Money(900));

        BillingResult result = processor.process(
                budgetState,
                reservation,
                event,
                Money.zero()
        );

        assertThat(result.reservation().status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.budgetState().totalReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(20_900));
        assertThat(result.budgetState().dailySpentAmount())
                .isEqualTo(new Money(5_900));
        assertThat(result.appliedAmount())
                .isEqualTo(new Money(900));
    }

    @Test
    void RESERVED_예약을_취소하면_예약액만_해제한다() {
        BudgetState budgetState = reservedBudgetState();
        BudgetReservation reservation =
                reservation(ReservationStatus.RESERVED);
        BillingEvent event =
                event(BillingEventType.CANCELLED, new Money(1_000));

        BillingResult result = processor.process(
                budgetState,
                reservation,
                event,
                Money.zero()
        );

        assertThat(result.reservation().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(result.budgetState().totalReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(result.budgetState().dailyReservedAmount())
                .isEqualTo(Money.zero());
        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(20_000));
        assertThat(result.appliedAmount())
                .isEqualTo(Money.zero());
    }

    @Test
    void CONFIRMED_과금을_취소하면_소진액을_차감한다() {
        BudgetState budgetState = confirmedBudgetState();
        BudgetReservation reservation =
                reservation(ReservationStatus.CONFIRMED);
        BillingEvent event =
                event(BillingEventType.CANCELLED, new Money(900));

        BillingResult result = processor.process(
                budgetState,
                reservation,
                event,
                new Money(900)
        );

        assertThat(result.reservation().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(20_000));
        assertThat(result.budgetState().dailySpentAmount())
                .isEqualTo(new Money(5_000));
        assertThat(result.appliedAmount())
                .isEqualTo(Money.zero());
    }

    @Test
    void 확정_과금액을_더_큰_금액으로_보정한다() {
        BillingResult result = processor.process(
                confirmedBudgetState(),
                reservation(ReservationStatus.CONFIRMED),
                event(BillingEventType.ADJUSTED, new Money(1_200)),
                new Money(900)
        );

        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(21_200));
        assertThat(result.budgetState().dailySpentAmount())
                .isEqualTo(new Money(6_200));
        assertThat(result.appliedAmount())
                .isEqualTo(new Money(1_200));
    }

    @Test
    void 확정_과금액을_더_작은_금액으로_보정한다() {
        BillingResult result = processor.process(
                confirmedBudgetState(),
                reservation(ReservationStatus.CONFIRMED),
                event(BillingEventType.ADJUSTED, new Money(800)),
                new Money(900)
        );

        assertThat(result.budgetState().totalSpentAmount())
                .isEqualTo(new Money(20_800));
        assertThat(result.budgetState().dailySpentAmount())
                .isEqualTo(new Money(5_800));
        assertThat(result.appliedAmount())
                .isEqualTo(new Money(800));
    }

    @Test
    void 확정되지_않은_예약은_과금액을_보정할_수_없다() {
        assertThatThrownBy(() ->
                processor.process(
                        reservedBudgetState(),
                        reservation(ReservationStatus.RESERVED),
                        event(BillingEventType.ADJUSTED, new Money(1_200)),
                        Money.zero()
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("확정된 예약만 과금액을 보정할 수 있습니다");
    }

    @Test
    void 취소_금액이_현재_적용된_과금액과_다르면_취소할_수_없다() {
        assertThatThrownBy(() ->
                processor.process(
                        confirmedBudgetState(),
                        reservation(ReservationStatus.CONFIRMED),
                        event(BillingEventType.CANCELLED, new Money(800)),
                        new Money(900)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("취소 금액은 현재 적용된 과금액과 일치해야 합니다");
    }

    @Test
    void 이벤트와_예약의_reservationId가_다르면_처리할_수_없다() {
        BillingEvent event = new BillingEvent(
                "event-1",
                "reservation-2",
                BillingEventType.CHARGED,
                new Money(900),
                occurredAt
        );

        assertThatThrownBy(() ->
                processor.process(
                        reservedBudgetState(),
                        reservation(ReservationStatus.RESERVED),
                        event,
                        Money.zero()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이벤트와 예약의 reservationId가 일치해야 합니다");
    }

    @Test
    void 과금_이벤트_시각이_예약_시각보다_이르면_처리할_수_없다() {
        BillingEvent event = new BillingEvent(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                new Money(900),
                reservedAt.minusSeconds(1)
        );

        assertThatThrownBy(() ->
                processor.process(
                        reservedBudgetState(),
                        reservation(ReservationStatus.RESERVED),
                        event,
                        Money.zero()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("과금 이벤트 시각은 예약 시각보다 이전일 수 없습니다");
    }

    private BudgetState reservedBudgetState() {
        return budgetState(
                new Money(20_000),
                new Money(1_000),
                new Money(5_000),
                new Money(1_000)
        );
    }

    private BudgetState confirmedBudgetState() {
        return budgetState(
                new Money(20_900),
                Money.zero(),
                new Money(5_900),
                Money.zero()
        );
    }

    private BudgetState budgetState(
            Money totalSpentAmount,
            Money totalReservedAmount,
            Money dailySpentAmount,
            Money dailyReservedAmount
    ) {
        return new BudgetState(
                "campaign-1",
                budgetDate,
                new Money(100_000),
                totalSpentAmount,
                totalReservedAmount,
                new Money(50_000),
                dailySpentAmount,
                dailyReservedAmount
        );
    }

    private BudgetReservation reservation(
            ReservationStatus status
    ) {
        return new BudgetReservation(
                "reservation-1",
                "campaign-1",
                budgetDate,
                new Money(1_000),
                status,
                reservedAt,
                reservedAt.plusSeconds(300)
        );
    }

    private BillingEvent event(
            BillingEventType eventType,
            Money actualAmount
    ) {
        return new BillingEvent(
                "event-1",
                "reservation-1",
                eventType,
                actualAmount,
                occurredAt
        );
    }
}
