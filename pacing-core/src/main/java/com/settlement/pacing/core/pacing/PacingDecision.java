package com.settlement.pacing.core.pacing;

public record PacingDecision(
        /* 광고 후보를 통과시킬지 차단할지에 대한 최종 결과 */
        DecisionType decisionType,

        /* 이번 판단에 적용된 광고 통과 비율 */
        Rate pacingRate
) {
    public PacingDecision {
        if (decisionType == null || pacingRate == null) {
            throw new IllegalArgumentException("결정 타입과 비율 값이 null일 수 없습니다");
        }
    }

    public static PacingDecision pass(Rate pacingRate) {
        return new PacingDecision(DecisionType.PASS, pacingRate);
    }

    public static PacingDecision block() {
        return new PacingDecision(DecisionType.BLOCK, Rate.zero());
    }

    public boolean isPass() {
       return decisionType == DecisionType.PASS;
    }
}
