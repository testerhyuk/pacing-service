package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;

import java.time.Instant;

public class AsapTargetSpendRateCalculator  implements TargetSpendRateCalculator {
    @Override
    public Rate calculate(Campaign campaign, Instant now) {
        if (campaign == null || now == null) {
            throw new IllegalArgumentException("캠페인과 현재 시각은 null일 수 없습니다");
        }

        // 캠페인 시작 전에는 예산을 소진하지 않는다
        if (now.isBefore(campaign.startAt())) return Rate.zero();

        return Rate.full();
    }
}
