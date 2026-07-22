package com.settlement.pacing.core.campaign;

import java.time.Instant;

public record Campaign(
        String campaignId,
        CampaignStatus status,
        Instant startAt,
        Instant endAt,
        PacingStrategy pacingStrategy
) {
    public Campaign {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId는 null이거나 비어있을 수 없습니다");
        }
        if (status == null || startAt == null || endAt == null || pacingStrategy == null) {
            throw new IllegalArgumentException("null인 값은 존재할 수 없습니다");
        }
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("시작일이 종료일보다 이전이어야 합니다");
        }
    }

    /**
     * 캠페인이 현재 집행 가능한 상태인지 확인한다.
     */
    public boolean isActive() {
        return status == CampaignStatus.ACTIVE;
    }

    /**
     * 주어진 시각이 캠페인 집행 기간에 포함되는지 확인한다.
     */
    public boolean isWithinPeriodAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("현재 시각은 null일 수 없습니다");
        }

        return !now.isBefore(startAt)
                && now.isBefore(endAt);
    }

    /**
     * 캠페인 상태와 집행 기간을 모두 확인한다.
     */
    public boolean isActiveAt(Instant now) {
        return isActive() && isWithinPeriodAt(now);
    }
}
