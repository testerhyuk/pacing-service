package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;

/**
 * 다음 구간의 목표 금액과 최근 트래픽의 예약 수용량을 이용해
 * 캠페인의 PASS 비율을 계산한다.
 */
public class CapacityBasedPacingRateCalculator
        implements PacingRateCalculator {
    private final long minimumPassCount;
    private final double smoothingFactor;
    private final double maxRateChange;
    private final double explorationStep;

    public CapacityBasedPacingRateCalculator(
            long minimumPassCount,
            double smoothingFactor,
            double maxRateChange,
            double explorationStep
    ) {
        if (minimumPassCount <= 0L) {
            throw new IllegalArgumentException(
                    "최소 PASS 표본 수는 0보다 커야 합니다"
            );
        }

        validateUnitRate(smoothingFactor, "비율 평활 계수");
        validateUnitRate(maxRateChange, "최대 비율 변경 폭");
        validateUnitRate(explorationStep, "탐색 증가 폭");

        this.minimumPassCount = minimumPassCount;
        this.smoothingFactor = smoothingFactor;
        this.maxRateChange = maxRateChange;
        this.explorationStep = explorationStep;
    }

    @Override
    public Rate calculate(
            Rate currentPacingRate,
            BudgetState budgetState,
            Rate currentTargetSpendRate,
            Rate nextTargetSpendRate,
            PacingObservation observation
    ) {
        if (currentPacingRate == null
                || budgetState == null
                || currentTargetSpendRate == null
                || nextTargetSpendRate == null
                || observation == null) {
            throw new IllegalArgumentException(
                    "페이싱 비율 계산 값은 null일 수 없습니다"
            );
        }

        if (currentTargetSpendRate.equals(Rate.full())) {
            return Rate.full();
        }

        double remainingCurveShare =
                1.0 - currentTargetSpendRate.value();
        double nextIntervalCurveShare = Math.max(
                0.0,
                nextTargetSpendRate.value()
                        - currentTargetSpendRate.value()
        );

        if (nextIntervalCurveShare <= 0.0) {
            return currentPacingRate;
        }

        double targetAmount = budgetState
                .totalAvailableAmount()
                .amount()
                * nextIntervalCurveShare
                / remainingCurveShare;

        targetAmount = Math.min(
                targetAmount,
                budgetState.availableAmount().amount()
        );

        if (targetAmount <= 0.0) {
            return Rate.zero();
        }

        if (observation.decisionCount() == 0L) {
            return currentPacingRate;
        }

        if (observation.passCount() < minimumPassCount) {
            return currentPacingRate.equals(Rate.zero())
                    ? explore(currentPacingRate)
                    : currentPacingRate;
        }

        double fullPassAmount =
                observation.estimatedFullPassAmountPerInterval();

        if (fullPassAmount <= 0.0) {
            return explore(currentPacingRate);
        }

        double requiredRate = Math.max(
                0.0,
                Math.min(targetAmount / fullPassAmount, 1.0)
        );

        return moveToward(currentPacingRate, requiredRate);
    }

    private Rate explore(Rate currentPacingRate) {
        double increase = Math.min(
                explorationStep,
                maxRateChange
        );
        return new Rate(Math.min(
                currentPacingRate.value() + increase,
                1.0
        ));
    }

    private Rate moveToward(
            Rate currentPacingRate,
            double requiredRate
    ) {
        double requestedChange =
                (requiredRate - currentPacingRate.value())
                        * smoothingFactor;
        double boundedChange = Math.max(
                -maxRateChange,
                Math.min(requestedChange, maxRateChange)
        );
        double adjustedRate = Math.max(
                0.0,
                Math.min(
                        currentPacingRate.value() + boundedChange,
                        1.0
                )
        );

        return new Rate(adjustedRate);
    }

    private void validateUnitRate(
            double value,
            String fieldName
    ) {
        if (!Double.isFinite(value)
                || value <= 0.0
                || value > 1.0) {
            throw new IllegalArgumentException(
                    fieldName + "는 0보다 크고 1 이하여야 합니다"
            );
        }
    }
}
