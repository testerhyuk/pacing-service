package com.settlement.pacing.core.pacing;

/**
 * 광고 후보 판단 결과와 다음 요청에 사용할 페이싱 상태를 함께 전달한다.
 */
public record PacingResult(
        PacingDecision decision,
        PacingState pacingState
) {
    public PacingResult {
        if (decision == null || pacingState == null) {
            throw new IllegalArgumentException("페이싱 판단 결과와 페이싱 상태는 null일 수 없습니다");
        }
    }
}
