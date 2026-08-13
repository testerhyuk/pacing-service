package com.settlement.pacing.core.billing;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;

/**
 * 과금 이벤트에 따라 예약 상태와 예산 상태를 함께 변경한다.
 */
public class BillingEventProcessor {

    public BillingResult process(
            BudgetState budgetState,
            BudgetReservation reservation,
            BillingEvent event,
            Money appliedAmount
    ) {
        validate(
                budgetState,
                reservation,
                event,
                appliedAmount
        );

        return switch (event.eventType()) {
            case CHARGED -> charge(
                    budgetState,
                    reservation,
                    event
            );

            case CANCELLED -> cancel(
                    budgetState,
                    reservation,
                    event,
                    appliedAmount
            );

            case ADJUSTED -> adjust(
                    budgetState,
                    reservation,
                    event,
                    appliedAmount
            );
        };
    }

    private BillingResult charge(
            BudgetState budgetState,
            BudgetReservation reservation,
            BillingEvent event
    ) {
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "이미 확정된 예약입니다"
            );
        }

        if (reservation.status() == ReservationStatus.CANCELLED) {
            throw new IllegalStateException(
                    "취소된 예약은 과금할 수 없습니다"
            );
        }

        BudgetState nextBudgetState;

        if (reservation.status() == ReservationStatus.RESERVED) {
            nextBudgetState = budgetState.confirm(
                    reservation.amount(),
                    event.targetAppliedAmount()
            );
        } else {
            // EXPIRED 예약은 예약액이 이미 해제됐으므로 소진액만 증가시킨다.
            nextBudgetState = budgetState.addSpent(
                    event.targetAppliedAmount()
            );
        }

        BudgetReservation nextReservation =
                reservation.confirm();

        return new BillingResult(
                nextBudgetState,
                nextReservation,
                event.targetAppliedAmount()
        );
    }

    private BillingResult cancel(
            BudgetState budgetState,
            BudgetReservation reservation,
            BillingEvent event,
            Money appliedAmount
    ) {
        BudgetState nextBudgetState =
                switch (reservation.status()) {
                    case RESERVED -> {
                        if (!event.targetAppliedAmount().isZero()) {
                            throw new IllegalArgumentException(
                                    "확정 전 예약 취소 후 적용 금액은 0이어야 합니다"
                            );
                        }

                        yield budgetState.release(
                                reservation.amount()
                        );
                    }

                    case CONFIRMED -> {
                        if (appliedAmount.isLessThan(
                                event.targetAppliedAmount()
                        )) {
                            throw new IllegalArgumentException(
                                    "취소 후 적용 금액은 현재 적용 금액보다 클 수 없습니다"
                            );
                        }

                        yield budgetState.adjustSpent(
                                appliedAmount,
                                event.targetAppliedAmount()
                        );
                    }

                    case EXPIRED -> {
                        if (!event.targetAppliedAmount().isZero()) {
                            throw new IllegalArgumentException(
                                    "만료 예약 취소 후 적용 금액은 0이어야 합니다"
                            );
                        }
                        yield budgetState;
                    }

                    case CANCELLED -> {
                        if (!event.targetAppliedAmount().isZero()) {
                            throw new IllegalArgumentException(
                                    "취소 이벤트는 취소된 과금액을 증가시킬 수 없습니다"
                            );
                        }
                        yield budgetState;
                    }
                };

        return new BillingResult(
                nextBudgetState,
                reservation.reconcileAppliedAmount(
                        event.targetAppliedAmount()
                ),
                event.targetAppliedAmount()
        );
    }

    private BillingResult adjust(
            BudgetState budgetState,
            BudgetReservation reservation,
            BillingEvent event,
            Money appliedAmount
    ) {
        if (reservation.status() != ReservationStatus.CONFIRMED
                && reservation.status() != ReservationStatus.CANCELLED) {
            throw new IllegalStateException(
                    "확정 또는 취소된 예약만 과금액을 보정할 수 있습니다"
            );
        }

        BudgetState nextBudgetState =
                budgetState.adjustSpent(
                        appliedAmount,
                        event.targetAppliedAmount()
                );

        return new BillingResult(
                nextBudgetState,
                reservation.reconcileAppliedAmount(
                        event.targetAppliedAmount()
                ),
                event.targetAppliedAmount()
        );
    }

    private void validate(
            BudgetState budgetState,
            BudgetReservation reservation,
            BillingEvent event,
            Money appliedAmount
    ) {
        if (budgetState == null
                || reservation == null
                || event == null
                || appliedAmount == null) {
            throw new IllegalArgumentException(
                    "과금 처리에 필요한 값은 null일 수 없습니다"
            );
        }

        if (!event.reservationId().equals(
                reservation.reservationId()
        )) {
            throw new IllegalArgumentException(
                    "이벤트와 예약의 reservationId가 일치해야 합니다"
            );
        }

        if (!budgetState.campaignId().equals(
                reservation.campaignId()
        )) {
            throw new IllegalArgumentException(
                    "예산 상태와 예약의 campaignId가 일치해야 합니다"
            );
        }

        if (!budgetState.budgetDate().equals(
                reservation.budgetDate()
        )) {
            throw new IllegalArgumentException(
                    "예산 상태와 예약의 budgetDate가 일치해야 합니다"
            );
        }

        if (event.occurredAt().isBefore(
                reservation.reservedAt()
        )) {
            throw new IllegalArgumentException(
                    "과금 이벤트 시각은 예약 시각보다 이전일 수 없습니다"
            );
        }
    }
}
