package com.settlement.pacing.core.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignTest {
    Instant startAt;
    Instant endAt;

    @BeforeEach
    void setUp() {
        startAt = Instant.parse("2026-07-21T00:00:00Z");
        endAt = Instant.parse("2026-07-22T00:00:00Z");
    }

    @Test
    void 시작_시각부터_캠페인이_활성화된다() {
        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                startAt,
                endAt,
                PacingStrategy.EVEN
        );

        assertThat(campaign.isActiveAt(startAt)).isTrue();
    }

    @Test
    void 종료_시각에는_캠페인이_활성화되지_않는다() {
        Instant startAt = Instant.parse("2026-07-21T00:00:00Z");
        Instant endAt = Instant.parse("2026-07-22T00:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                startAt,
                endAt,
                PacingStrategy.EVEN
        );

        assertThat(campaign.isActiveAt(endAt)).isFalse();
    }

    @Test
    void 일시_중지된_캠페인은_집행_기간이어도_활성화되지_않는다() {
        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.PAUSED,
                startAt,
                endAt,
                PacingStrategy.EVEN
        );

        Instant now = Instant.parse("2026-07-21T12:00:00Z");

        assertThat(campaign.isActiveAt(now)).isFalse();
    }
}