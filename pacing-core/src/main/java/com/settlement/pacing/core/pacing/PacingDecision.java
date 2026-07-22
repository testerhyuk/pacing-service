package com.settlement.pacing.core.pacing;

public record PacingDecision(
        /* 광고 후보를 통과시킬지 차단할지에 대한 최종 결과 */
        DecisionType decisionType,

        /* 통과 또는 차단된 구체적인 이유 */
        DecisionReason reason,

        /* 이번 판단에 적용된 광고 통과 비율 */
        Rate pacingRate

) {
    public PacingDecision {
        if (decisionType == null || pacingRate == null || reason == null) {
            throw new IllegalArgumentException("결정 타입, 사유와 비율은 null일 수 없습니다");
        }

        if (decisionType == DecisionType.PASS && reason != DecisionReason.PASS) {
            throw new IllegalArgumentException("PASS 결정의 사유는 PASS여야 합니다");
        }

        if (decisionType == DecisionType.BLOCK && reason == DecisionReason.PASS) {
            throw new IllegalArgumentException("BLOCK 결정의 사유는 PASS일 수 없습니다");
        }
    }

    public static PacingDecision pass(Rate pacingRate) {
        return new PacingDecision(DecisionType.PASS, DecisionReason.PASS, pacingRate);
    }

    /**
     * 페이싱 비율 샘플링에서 탈락한 경우 사용한다.
     */
    public static PacingDecision block(Rate pacingRate) {
        return block(
                DecisionReason.PACING_REJECTED,
                pacingRate
        );
    }

    /**
     * 구체적인 차단 사유가 있는 경우 사용한다.
     */
    public static PacingDecision block(
            DecisionReason reason,
            Rate pacingRate
    ) {
        return new PacingDecision(
                DecisionType.BLOCK,
                reason,
                pacingRate
        );
    }

    public boolean isPass() {
       return decisionType == DecisionType.PASS;
    }
}
