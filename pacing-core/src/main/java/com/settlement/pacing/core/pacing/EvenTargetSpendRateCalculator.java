package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;

import java.time.Duration;
import java.time.Instant;

public class EvenTargetSpendRateCalculator implements TargetSpendRateCalculator {
    @Override
    public Rate calculate(Campaign campaign, Instant now) {
        if (campaign == null || now == null) {
            throw new IllegalArgumentException("캠페인과 현재 시각은 null일 수 없습니다");
        }

        // 캠페인 시작 전과 시작 시점에는 아직 소진 목표가 없다
        if (!now.isAfter(campaign.startAt())) return Rate.zero();

        // 캠페인 종료 시점부터는 전체 예산 소진이 목표
        if (!now.isBefore(campaign.endAt())) return Rate.full();

        Duration totalDuration = Duration.between(campaign.startAt(), campaign.endAt());
        Duration elapsedDuration = Duration.between(campaign.startAt(), now);

        double targetRate = toSeconds(elapsedDuration) / toSeconds(totalDuration);

        return new Rate(targetRate);
    }

    private double toSeconds(Duration duration) {
        return duration.toSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
