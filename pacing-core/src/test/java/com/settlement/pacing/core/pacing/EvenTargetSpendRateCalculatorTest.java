package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvenTargetSpendRateCalculatorTest {
    private final EvenTargetSpendRateCalculator calculator = new EvenTargetSpendRateCalculator();

    private Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-21T10:00:00Z"),
                PacingStrategy.EVEN
        );
    }

    @Test
    void 캠페인_시작_전에는_목표_소진율이_0이다() {
        Instant now = Instant.parse("2026-07-20T23:00:00Z");

        Rate result = calculator.calculate(campaign, now);

        assertThat(result).isEqualTo(Rate.zero());
    }

    @Test
    void 캠페인_시작_시점에는_목표_소진율이_0이다() {
        Rate result = calculator.calculate(campaign, campaign.startAt());

        assertThat(result).isEqualTo(Rate.zero());
    }

    @Test
    void 캠페인_절반이_지나면_목표_소진율은_50퍼센트다() {
        Instant now = Instant.parse("2026-07-21T05:00:00Z");

        Rate result = calculator.calculate(campaign, now);

        assertThat(result.value()).isCloseTo(0.5, within(0.000000001));
    }

    @Test
    void 캠페인_종료_시점에는_목표_소진율이_100퍼센트다() {
        Rate result = calculator.calculate(campaign, campaign.endAt());

        assertThat(result).isEqualTo(Rate.full());
    }

    @Test
    void 캠페인_종료_후에는_목표_소진율이_100퍼센트다() {
        Instant now = Instant.parse("2026-07-21T11:00:00Z");

        Rate result = calculator.calculate(campaign, now);

        assertThat(result).isEqualTo(Rate.full());
    }
}