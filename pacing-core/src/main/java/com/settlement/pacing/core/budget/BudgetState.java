package com.settlement.pacing.core.budget;

import java.time.LocalDate;

public record BudgetState(
        LocalDate budgetDate,
        Money totalBudget,
        Money spentAmount,
        Money reservedAmount
) {
    /**
     * 특정 날짜의 캠페인 예산 상태를 생성한다.
     * 실제 소진액과 예약액의 합은 전체 예산을 초과할 수 없다.
     */
    public BudgetState {
        // null 검증
        if (budgetDate == null || totalBudget == null || spentAmount == null || reservedAmount == null) {
            throw new IllegalArgumentException("null인 데이터는 존재할 수 없습니다");
        }

        // 예산 초과 검증
        Money effectiveSpend = spentAmount.add(reservedAmount);

        if (totalBudget.isLessThan(effectiveSpend)) {
            throw new IllegalArgumentException("예약 금액과 사용된 금액이 총 금액을 초과할 수 없습니다");
        }
    }

    /**
     * 전체 예산에서 확정 소진액과 예약액을 제외한 금액을 반환한다.
     * 현재 새로운 광고 요청에 추가로 예약할 수 있는 금액이다.
     */
    public Money availableAmount() {
        return totalBudget
                .subtract(spentAmount)
                .subtract(reservedAmount);
    }

    /**
     * 페이싱 계산에서 이미 사용된 것으로 간주할 금액을 반환한다.
     * 실제 소진액과 아직 과금되지 않은 예약액을 합산한다.
     */
    public Money effectiveSpend() {
       return reservedAmount.add(spentAmount);
    }

    /**
     * 현재 사용 가능한 예산으로 요청 금액을 예약할 수 있는지 확인한다.
     * 사용 가능한 예산이 요청 금액보다 크거나 같으면 true를 반환한다.
     */
    public boolean canReserve(Money amount) {
        if (amount == null) throw new IllegalArgumentException("예약 금액은 null일 수 없습니다");

        return availableAmount().isGreaterThanOrEqualTo(amount);
    }
}
