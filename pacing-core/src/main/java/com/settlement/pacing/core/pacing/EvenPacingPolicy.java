package com.settlement.pacing.core.pacing;

public class EvenPacingPolicy implements PacingPolicy {
    @Override
    public PacingDecision decide(PacingContext context, Rate sampleRate) {
        if (context == null || sampleRate == null) {
            throw new IllegalArgumentException("페이싱 컨텍스트와 샘플 비율은 null일 수 없습니다");
        }

        if (sampleRate.isLessThan(context.pacingRate())) {
            return PacingDecision.pass(context.pacingRate());
        }

        return PacingDecision.block(context.pacingRate());
    }
}
