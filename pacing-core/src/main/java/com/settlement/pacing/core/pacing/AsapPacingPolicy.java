package com.settlement.pacing.core.pacing;

public class AsapPacingPolicy implements PacingPolicy {
    @Override
    public PacingDecision decide(PacingContext context, Rate sampleRate) {
        if (sampleRate == null || context == null) {
            throw new IllegalArgumentException("페이싱 컨텍스트와 샘플 비율은 null일 수 없습니다");
        }

        return PacingDecision.pass(Rate.full());
    }
}
