package com.settlement.pacing.core.budget;

import java.time.LocalDate;

public record BudgetState(
        String campaignId,
        LocalDate budgetDate,
        Money totalBudget,
        Money totalSpentAmount,
        Money totalReservedAmount,
        Money dailyBudgetLimit,
        Money dailySpentAmount,
        Money dailyReservedAmount
) {
    /**
     * 특정 날짜의 캠페인 예산 상태를 생성한다.
     * 실제 소진액과 예약액의 합은 전체 예산을 초과할 수 없다.
     */
    public BudgetState {
        // null 검증
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId는 null이거나 비어있을 수 없습니다");
        }

        if (budgetDate == null || totalBudget == null || totalSpentAmount == null || totalReservedAmount == null
            || dailyBudgetLimit == null || dailySpentAmount == null || dailyReservedAmount == null) {
            throw new IllegalArgumentException("null인 데이터는 존재할 수 없습니다");
        }

        // 일일 차감 금액은 캠페인 전체 금액을 초과할 수 없다
        if (totalSpentAmount.isLessThan(dailySpentAmount)) {
            throw new IllegalArgumentException("오늘 소진액은 전체 소진액을 초과할 수 없습니다");
        }

        // 일일 예약액은 전체 예약액을 초과할 수 없다
        if (totalReservedAmount.isLessThan(dailyReservedAmount)) {
            throw new IllegalArgumentException("오늘 예약액은 전체 예약액을 초과할 수 없습니다");
        }
    }
    /**
     * 캠페인 전체 예산에서 추가로 사용할 수 있는 금액을 반환한다.
     */
    public Money totalAvailableAmount() {
        Money effectiveSpend = totalEffectiveSpend();

        if (totalBudget.isLessThan(effectiveSpend)) {
            return Money.zero();
        }

        return totalBudget.subtract(effectiveSpend);
    }

    /**
     * 오늘 일 예산 한도에서 추가로 사용할 수 있는 금액을 반환한다.
     */
    public Money dailyAvailableAmount() {
        Money effectiveSpend = dailyEffectiveSpend();

        if (dailyBudgetLimit.isLessThan(effectiveSpend)) {
            return Money.zero();
        }

        return dailyBudgetLimit.subtract(effectiveSpend);
    }

    /**
     * 전체 남은 예산과 오늘 남은 한도 중 더 적은 금액을 반환한다.
     */
    public Money availableAmount() {
        return totalAvailableAmount().min(dailyAvailableAmount());
    }

    /**
     * 캠페인 전체의 확정 소진액과 예약액을 합산한다.
     * 페이싱의 실제 소진율 계산에 사용한다.
     */
    public Money totalEffectiveSpend() {
        return totalSpentAmount.add(totalReservedAmount);
    }

    /**
     * 오늘의 확정 소진액과 예약액을 합산한다.
     * 일 예산 한도 확인에 사용한다.
     */
    public Money dailyEffectiveSpend() {
        return dailySpentAmount.add(dailyReservedAmount);
    }

    /**
     * 전체 예산과 일 예산 한도를 모두 만족할 때만 예약할 수 있다.
     */
    public boolean canReserve(Money amount) {
        if (amount == null) throw new IllegalArgumentException("예약 금액은 null일 수 없습니다");

        if (amount.isZero()) return false;

        return availableAmount().isGreaterThanOrEqualTo(amount);
    }

    /**
     * 예산을 예약하고 전체·일일 예약액을 증가시킨다.
     */
    public BudgetState reserve(Money amount) {
        validatePositiveAmount(amount);

        if (!canReserve(amount)) {
            throw new IllegalStateException(
                    "사용 가능한 예산이 부족합니다"
            );
        }

        return withAmounts(
                totalSpentAmount,
                totalReservedAmount.add(amount),
                dailySpentAmount,
                dailyReservedAmount.add(amount)
        );
    }

    /**
     * 예약액을 해제하고 실제 과금액을 확정 소진액에 반영한다.
     */
    public BudgetState confirm(
            Money reservedAmount,
            Money actualAmount
    ) {
        validatePositiveAmount(reservedAmount);
        validatePositiveAmount(actualAmount);

        return withAmounts(
                totalSpentAmount.add(actualAmount),
                totalReservedAmount.subtract(reservedAmount),
                dailySpentAmount.add(actualAmount),
                dailyReservedAmount.subtract(reservedAmount)
        );
    }

    /**
     * 취소되거나 만료된 예약 금액을 해제한다.
     */
    public BudgetState release(Money reservedAmount) {
        validatePositiveAmount(reservedAmount);

        return withAmounts(
                totalSpentAmount,
                totalReservedAmount.subtract(reservedAmount),
                dailySpentAmount,
                dailyReservedAmount.subtract(reservedAmount)
        );
    }

    /**
     * 이미 예약이 해제된 지연 과금을 확정 소진액에 추가한다.
     */
    public BudgetState addSpent(Money amount) {
        validatePositiveAmount(amount);

        return withAmounts(
                totalSpentAmount.add(amount),
                totalReservedAmount,
                dailySpentAmount.add(amount),
                dailyReservedAmount
        );
    }

    /**
     * 취소 또는 환불된 확정 과금을 소진액에서 차감한다.
     */
    public BudgetState subtractSpent(Money amount) {
        validatePositiveAmount(amount);

        return withAmounts(
                totalSpentAmount.subtract(amount),
                totalReservedAmount,
                dailySpentAmount.subtract(amount),
                dailyReservedAmount
        );
    }

    /**
     * 기존 과금액을 보정된 최종 과금액으로 변경한다.
     */
    public BudgetState adjustSpent(
            Money currentAmount,
            Money adjustedAmount
    ) {
        validateAmount(currentAmount);
        validateAmount(adjustedAmount);

        if (currentAmount.equals(adjustedAmount)) {
            return this;
        }

        if (currentAmount.isLessThan(adjustedAmount)) {
            return addSpent(
                    adjustedAmount.subtract(currentAmount)
            );
        }

        return subtractSpent(
                currentAmount.subtract(adjustedAmount)
        );
    }

    private void validateAmount(Money amount) {
        if (amount == null) {
            throw new IllegalArgumentException(
                    "처리할 금액은 null일 수 없습니다"
            );
        }
    }

    private void validatePositiveAmount(Money amount) {
        if (amount == null) {
            throw new IllegalArgumentException(
                    "처리할 금액은 null일 수 없습니다"
            );
        }

        if (amount.isZero()) {
            throw new IllegalArgumentException(
                    "처리할 금액은 0보다 커야 합니다"
            );
        }
    }

    private BudgetState withAmounts(
            Money newTotalSpentAmount,
            Money newTotalReservedAmount,
            Money newDailySpentAmount,
            Money newDailyReservedAmount
    ) {
        return new BudgetState(
                campaignId,
                budgetDate,
                totalBudget,
                newTotalSpentAmount,
                newTotalReservedAmount,
                dailyBudgetLimit,
                newDailySpentAmount,
                newDailyReservedAmount
        );
    }
}
