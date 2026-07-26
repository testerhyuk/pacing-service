package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class PeakWeightedTargetSpendRateCalculatorTest {
    private PeakWeightedTargetSpendRateCalculator calculator;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        PeakTimeWindow peakTimeWindow = new PeakTimeWindow(
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                ZoneId.of("Asia/Seoul")
        );

        TrafficWeight trafficWeight = new TrafficWeight(0.5, 1.5);

        calculator = new PeakWeightedTargetSpendRateCalculator(
                peakTimeWindow,
                trafficWeight
        );

        campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,

                // 한국 시각 2026-07-21 00:00
                Instant.parse("2026-07-20T15:00:00Z"),

                // 한국 시각 2026-07-22 00:00
                Instant.parse("2026-07-21T15:00:00Z"),

                PacingStrategy.PEAK_WEIGHTED
        );
    }

    @Test
    void 캠페인_시작_시점에는_목표_소진율이_0이다() {
        Rate result = calculator.calculate(campaign, campaign.startAt());

        assertThat(result).isEqualTo(Rate.zero());
    }

    @Test
    void 피크_전에는_일반_시간_가중치로_목표_소진율을_계산한다() {
        // 한국 시각 12:00
        Instant now = Instant.parse("2026-07-21T03:00:00Z");

        Rate result = calculator.calculate(campaign, now);

        /*
         * 현재까지 일반 시간 12시간 × 0.5 = 6
         * 전체 가중 시간 = 17
         * 목표 소진율 = 6 / 17
         */
        assertThat(result.value()).isCloseTo(6.0 / 17.0, within(0.000000001));
    }

    @Test
    void 피크_시간에는_피크_가중치를_반영해_목표_소진율을_계산한다() {
        // 한국 시각 20:00
        Instant now = Instant.parse("2026-07-21T11:00:00Z");

        Rate result = calculator.calculate(campaign, now);

        /*
         * 일반 시간 18시간 × 0.5 = 9
         * 피크 시간 2시간 × 1.5 = 3
         * 현재까지 가중 시간 = 12
         * 전체 가중 시간 = 17
         * 목표 소진율 = 12 / 17
         */
        assertThat(result.value()).isCloseTo(12.0 / 17.0, within(0.000000001));
    }

    @Test
    void 캠페인_종료_시점에는_목표_소진율이_100퍼센트다() {
        Rate result = calculator.calculate(campaign, campaign.endAt());

        assertThat(result).isEqualTo(Rate.full());
    }

    @Test
    void 장기_캠페인도_날짜별_반복_없이_빠르게_계산한다() {
        Campaign longCampaign = new Campaign(
                "campaign-long",
                CampaignStatus.ACTIVE,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2040-01-01T00:00:00Z"),
                PacingStrategy.PEAK_WEIGHTED
        );

        Rate result = assertTimeout(
                Duration.ofSeconds(1),
                () -> calculator.calculate(
                        longCampaign,
                        Instant.parse("2030-01-01T00:00:00Z")
                )
        );

        assertThat(result.value())
                .isBetween(0.49, 0.51);
    }
}
