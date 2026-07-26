package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;

public interface PacingRateCalculator {
    Rate calculate(
            Rate currentPacingRate,
            BudgetState budgetState,
            Rate currentTargetSpendRate,
            Rate nextTargetSpendRate,
            PacingObservation observation
    );
}
