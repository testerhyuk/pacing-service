package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;

public class ActualSpendRateCalculator {
    /**
     * 전체 예산을 기준으로 현재 실제 소진율을 계산한다.
     */
    public Rate calculate(BudgetState budgetState) {
        if (budgetState == null) throw new IllegalArgumentException("예산 상태는 null일 수 없습니다");

        if (budgetState.totalBudget().isZero()) throw new IllegalArgumentException("전체 예산은 0보다 커야 합니다");

        double actualSpendRate =
                (double) budgetState.totalEffectiveSpend().amount() / budgetState.totalBudget().amount();

        return new Rate(actualSpendRate);
    }
}
