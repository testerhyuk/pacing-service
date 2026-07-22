package com.settlement.pacing.core.billing;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;

/**
 * 과금 이벤트를 적용한 이후의 도메인 상태다.
 */
public record BillingResult(
        BudgetState budgetState,
        BudgetReservation reservation,
        Money appliedAmount
) {
    public BillingResult {
        if (budgetState == null
                || reservation == null
                || appliedAmount == null) {
            throw new IllegalArgumentException(
                    "과금 처리 결과는 null일 수 없습니다"
            );
        }
    }
}