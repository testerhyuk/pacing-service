package com.settlement.pacing.core.pacing;

public class PeakWeightedPacingPolicy implements PacingPolicy {
    private final TrafficWeight trafficWeight;

    public PeakWeightedPacingPolicy(TrafficWeight trafficWeight) {
        if (trafficWeight == null) {
            throw new IllegalArgumentException("트래픽 가중치는 null일 수 없습니다");
        }
        this.trafficWeight = trafficWeight;
    }

    @Override
    public PacingDecision decide(PacingContext context, Rate sampleRate) {
        if (context == null || sampleRate == null) {
            throw new IllegalArgumentException("페이싱 컨텍스트와 샘플 비율은 null일 수 없습니다");
        }

        Rate weightedRate = trafficWeight.applyTo(
                context.pacingRate(),
                context.trafficPeriod()
        );

        if (sampleRate.isLessThan(weightedRate)) {
            return PacingDecision.pass(weightedRate);
        }

        return PacingDecision.block();
    }
}
