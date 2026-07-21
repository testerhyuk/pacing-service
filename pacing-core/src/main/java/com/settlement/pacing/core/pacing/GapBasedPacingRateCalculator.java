package com.settlement.pacing.core.pacing;

public class GapBasedPacingRateCalculator implements PacingRateCalculator {
    // 목표 소진율과 실제 소진율의 차이를 현재 통과율에 얼마나 반영할지 정하는 값
    private final double adjustmentGain;

    public GapBasedPacingRateCalculator(double adjustmentGain) {
        if (!Double.isFinite(adjustmentGain)) {
            throw new IllegalArgumentException("조정 강도는 유한한 숫자여야 합니다");
        }

        if (adjustmentGain <= 0.0 || adjustmentGain > 1.0) {
            throw new IllegalArgumentException("조정 강도는 0보다 크고 1 이하여야 합니다");
        }

        this.adjustmentGain = adjustmentGain;
    }

    @Override
    public Rate calculate(Rate currentPacingRate, Rate targetSpendRate, Rate actualSpendRate) {
        if (currentPacingRate == null || targetSpendRate == null || actualSpendRate == null) {
            throw new IllegalArgumentException("페이싱 비율과 소진 비율은 null일 수 없습니다");
        }

        /*
         * 1차 페이싱 조정 공식이다.
         * 실제 운영 데이터를 확보한 뒤 계산식을 개선한다.
         */
        double spendGap = targetSpendRate.value() - actualSpendRate.value();
        double adjustedRate = currentPacingRate.value() + spendGap * adjustmentGain;
        double boundedRate = Math.max(0.0, Math.min(adjustedRate, 1.0));

        return new Rate(boundedRate);
    }
}
