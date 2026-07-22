package com.settlement.pacing.core.pacing;

public record PacingContext(
        /* 현재 시점까지 목표로 소진했어야 하는 예산 비율 */
        Rate targetSpendRate,
        /* 실제 소진액과 예약액을 합산한 현재 유효 소진 비율 */
        Rate actualSpendRate,
        /* 후보 광고 요청을 통과시킬 비율 */
        Rate pacingRate
) {
    public PacingContext {
        if (targetSpendRate == null
                || actualSpendRate == null
                || pacingRate == null
        ) {
            throw new IllegalArgumentException("페이싱 컨텍스트 값이 null일 수 없습니다");
        }
    }

    public boolean isUnderPaced() {
        return actualSpendRate.isLessThan(targetSpendRate);
    }
}
