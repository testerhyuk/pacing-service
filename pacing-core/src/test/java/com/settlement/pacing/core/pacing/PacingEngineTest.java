package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PacingEngineTest {
    @Test
    void 캠페인이_활성_상태가_아니면_BLOCK한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.PAUSED,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.1)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.CAMPAIGN_INACTIVE);
        assertThat(result.decision().pacingRate()).isEqualTo(new Rate(0.5));
    }

    @Test
    void 캠페인_집행_기간이_아니면_BLOCK한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-20T23:59:59Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.1)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.OUTSIDE_CAMPAIGN_PERIOD);
        assertThat(result.pacingState()).isEqualTo(pacingState);
    }

    @Test
    void 전체_예산이_소진되면_오늘_한도가_남아도_BLOCK한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(80_000),
                new Money(20_000),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.1)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.BUDGET_EXHAUSTED);
        assertThat(result.decision().pacingRate()).isEqualTo(new Rate(0.5));
    }

    @Test
    void 오늘_예산_한도가_소진되면_전체_예산이_남아도_BLOCK한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(1_000_000),
                new Money(200_000),
                new Money(50_000),

                new Money(100_000),
                new Money(80_000),
                new Money(20_000)
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.1)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.BUDGET_EXHAUSTED);
        assertThat(result.decision().pacingRate()).isEqualTo(new Rate(0.5));
    }

    @Test
    void 활성_캠페인은_조정된_통과율로_PASS를_판단한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.5)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.PASS);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.PASS);

        assertThat(result.decision().pacingRate().value()).isCloseTo(0.525, within(0.000000001));

        assertThat(result.pacingState().pacingRate().value()).isCloseTo(0.525, within(0.000000001));

        assertThat(result.pacingState().updatedAt()).isEqualTo(now);
    }

    @Test
    void 활성_캠페인이어도_샘플_비율이_조정된_통과율보다_크면_BLOCK한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");
        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.7)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.PACING_REJECTED);
        assertThat(result.decision().pacingRate().value()).isCloseTo(0.525, within(0.000000001));
    }

    @Test
    void 갱신_주기가_지나기_전에는_저장된_페이싱_비율로_판단한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(4)
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.51)
        );

        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.BLOCK);
        assertThat(result.decision().reason()).isEqualTo(DecisionReason.PACING_REJECTED);

        assertThat(result.decision().pacingRate()).isEqualTo(new Rate(0.5));

        assertThat(result.pacingState()).isEqualTo(pacingState);
    }

    @Test
    void 페이싱_요청과_캠페인의_campaignId가_다르면_판단할_수_없다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        PacingRequest request = new PacingRequest(
                "request-1",
                "campaign-2",
                now
        );

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        assertThatThrownBy(() ->
                engine.decide(
                        request,
                        campaign,
                        budgetState,
                        pacingState,
                        new Rate(0.1)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("페이싱 요청과 캠페인의 campaignId가 일치해야 합니다");
    }

    @Test
    void 캠페인과_예산_상태의_campaignId가_다르면_판단할_수_없다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                "campaign-2",
                LocalDate.of(2026, 7, 21),

                new Money(100_000),
                new Money(20_000),
                Money.zero(),

                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        assertThatThrownBy(() ->
                engine.decide(
                        createRequest(now),
                        campaign,
                        budgetState,
                        pacingState,
                        new Rate(0.1)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("캠페인과 예산 상태의 campaignId가 일치해야 합니다");
    }

    @Test
    void ASAP은_항상_100퍼센트로_PASS하고_저장_상태도_같은_비율로_갱신한다() {
        PacingEngine engine = createEngine();

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.ASAP
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(20_000),
                Money.zero(),
                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(4)
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.99)
        );

        assertThat(result.decision().decisionType())
                .isEqualTo(DecisionType.PASS);
        assertThat(result.decision().pacingRate())
                .isEqualTo(Rate.full());
        assertThat(result.pacingState().pacingRate())
                .isEqualTo(Rate.full());
        assertThat(result.pacingState().updatedAt())
                .isEqualTo(now);
    }

    @Test
    void PEAK_WEIGHTED는_피크_가중_목표_소진율로_페이싱_비율을_계산한다() {
        PacingEngine engine = createEngine();

        // 한국 시각 2026-07-21 20:00
        Instant now = Instant.parse("2026-07-21T11:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-20T15:00:00Z"),
                Instant.parse("2026-07-21T15:00:00Z"),
                PacingStrategy.PEAK_WEIGHTED
        );

        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(20_000),
                Money.zero(),
                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingState pacingState = new PacingState(
                new Rate(0.5),
                now.minusSeconds(5)
        );

        PacingResult result = engine.decide(
                createRequest(now),
                campaign,
                budgetState,
                pacingState,
                new Rate(0.7)
        );

        double targetSpendRate = 12.0 / 17.0;
        double expectedPacingRate =
                0.5 + 0.5 * (targetSpendRate - 0.2);

        assertThat(result.decision().decisionType())
                .isEqualTo(DecisionType.PASS);
        assertThat(result.decision().pacingRate().value())
                .isCloseTo(expectedPacingRate, within(0.000000001));
        assertThat(result.pacingState().pacingRate().value())
                .isCloseTo(expectedPacingRate, within(0.000000001));
        assertThat(result.pacingState().updatedAt())
                .isEqualTo(now);
    }

    private PacingRequest createRequest(Instant requestedAt) {
        return new PacingRequest(
                "request-1",
                "campaign-1",
                requestedAt
        );
    }

    private PacingEngine createEngine() {
        PacingPolicyResolver pacingPolicyResolver =
                new PacingPolicyResolver(
                        new EvenPacingPolicy(),
                        new PeakWeightedPacingPolicy(),
                        new AsapPacingPolicy()
                );

        PeakTimeWindow peakTimeWindow =
                new PeakTimeWindow(
                        LocalTime.of(18, 0),
                        LocalTime.of(23, 0),
                        ZoneId.of("Asia/Seoul")
                );

        TrafficWeight trafficWeight = new TrafficWeight(0.5, 1.5);

        TargetSpendRateCalculatorResolver targetResolver =
                new TargetSpendRateCalculatorResolver(
                        new EvenTargetSpendRateCalculator(),
                        new PeakWeightedTargetSpendRateCalculator(
                                peakTimeWindow,
                                trafficWeight
                        ),
                        new AsapTargetSpendRateCalculator()
                );

        return new PacingEngine(
                new GapBasedPacingRateCalculator(0.5),
                pacingPolicyResolver,
                targetResolver,
                new ActualSpendRateCalculator(),
                Duration.ofSeconds(5)
        );
    }
}
