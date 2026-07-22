package com.settlement.pacing.core.pacing;

import java.time.Duration;
import java.time.Instant;

/**
 * 캠페인의 현재 페이싱 비율과 마지막 비율 갱신 시각을 관리한다.
 *
 * 광고 후보의 PASS/BLOCK 판단은 요청마다 수행하지만,
 * 페이싱 비율은 설정된 갱신 주기가 지난 경우에만 다시 계산한다.
 */
public record PacingState(
        Rate pacingRate,
        Instant updatedAt
) {
    public PacingState {
        if (pacingRate == null || updatedAt == null) {
            throw new IllegalArgumentException("페이싱 비율과 갱신 시각은 null일 수 없습니다");
        }
    }

    /**
     * 지정된 갱신 주기가 지났는지 확인한다.
     */
    public boolean shouldUpdateAt(Instant now, Duration updateInterval) {
        if (now == null || updateInterval == null) {
            throw new IllegalArgumentException("현재 시각과 갱신 주기는 null일 수 없습니다");
        }

        if (updateInterval.isZero() || updateInterval.isNegative()) {
            throw new IllegalArgumentException("갱신 주기는 0보다 커야 합니다");
        }

        Instant nextUpdateAt = updatedAt.plus(updateInterval);

        return !now.isBefore(nextUpdateAt);
    }
}
