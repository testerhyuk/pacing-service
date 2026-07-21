package com.settlement.pacing.core.pacing;

public interface PacingRateCalculator {
    Rate calculate(
            Rate currentPacingRate,
            Rate targetSpendRate,
            Rate actualSpendRate
    );
}
