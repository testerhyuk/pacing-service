package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.PacingStrategy;

public class TargetSpendRateCalculatorResolver {
    private final TargetSpendRateCalculator evenCalculator;
    private final TargetSpendRateCalculator peakWeightedCalculator;
    private final TargetSpendRateCalculator asapCalculator;

    public TargetSpendRateCalculatorResolver(
            TargetSpendRateCalculator evenCalculator,
            TargetSpendRateCalculator peakWeightedCalculator,
            TargetSpendRateCalculator asapCalculator
    ) {
        if (evenCalculator == null
                || peakWeightedCalculator == null
                || asapCalculator == null) {
            throw new IllegalArgumentException("목표 소진율 계산기는 null일 수 없습니다");
        }

        this.evenCalculator = evenCalculator;
        this.peakWeightedCalculator = peakWeightedCalculator;
        this.asapCalculator = asapCalculator;
    }

    /**
     * 캠페인 전략에 맞는 목표 소진율 계산기를 반환한다.
     */
    public TargetSpendRateCalculator resolve(PacingStrategy pacingStrategy) {
        if (pacingStrategy == null) throw new IllegalArgumentException("페이싱 전략은 null일 수 없습니다");

        return switch (pacingStrategy) {
            case EVEN -> evenCalculator;
            case PEAK_WEIGHTED -> peakWeightedCalculator;
            case ASAP -> asapCalculator;
        };
    }
}
