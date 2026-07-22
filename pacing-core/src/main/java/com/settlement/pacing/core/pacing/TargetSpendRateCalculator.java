package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;

import java.time.Instant;

public interface TargetSpendRateCalculator {
    /**
     * 현재 시점까지 소진했어야 하는 목표 비율을 계산한다.
     */
    Rate calculate(Campaign campaign, Instant now);
}
