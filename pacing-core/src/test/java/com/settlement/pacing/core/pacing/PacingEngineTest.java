package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PacingEngineTest {
    @Test
    void 캠페인이_활성_상태가_아니면_BLOCK한다() {
        PacingPolicyResolver resolver = new PacingPolicyResolver(
                new EvenPacingPolicy(),
                new PeakWeightedPacingPolicy(
                        new TrafficWeight(0.5, 1.5)
                ),
                new AsapPacingPolicy()
        );

        PacingEngine engine = new PacingEngine(
                new GapBasedPacingRateCalculator(0.5),
                resolver
        );

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.PAUSED,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingContext context = new PacingContext(
                new Rate(0.5),
                new Rate(0.2),
                new Rate(0.5),
                TrafficPeriod.NORMAL
        );

        PacingDecision result = engine.decide(
                campaign,
                budgetState,
                context,
                new Rate(0.1),
                now
        );

        assertThat(result.decisionType()).isEqualTo(DecisionType.BLOCK);
    }

    @Test
    void 사용_가능한_예산이_없으면_BLOCK한다() {
        PacingPolicyResolver resolver = new PacingPolicyResolver(
                new EvenPacingPolicy(),
                new PeakWeightedPacingPolicy(
                        new TrafficWeight(0.5, 1.5)
                ),
                new AsapPacingPolicy()
        );

        PacingEngine engine = new PacingEngine(
                new GapBasedPacingRateCalculator(0.5),
                resolver
        );

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(80_000),
                new Money(20_000)
        );

        PacingContext context = new PacingContext(
                new Rate(0.5),
                new Rate(0.2),
                new Rate(0.5),
                TrafficPeriod.NORMAL
        );

        PacingDecision result = engine.decide(
                campaign,
                budgetState,
                context,
                new Rate(0.1),
                now
        );

        assertThat(result.decisionType()).isEqualTo(DecisionType.BLOCK);
    }

    @Test
    void 활성_캠페인은_조정된_통과율로_PASS를_판단한다() {
        PacingPolicyResolver resolver = new PacingPolicyResolver(
                new EvenPacingPolicy(),
                new PeakWeightedPacingPolicy(
                        new TrafficWeight(0.5, 1.5)
                ),
                new AsapPacingPolicy()
        );

        PacingEngine engine = new PacingEngine(
                new GapBasedPacingRateCalculator(0.5),
                resolver
        );

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.5),
                TrafficPeriod.NORMAL
        );

        PacingDecision result = engine.decide(
                campaign,
                budgetState,
                context,
                new Rate(0.55),
                now
        );

        assertThat(result.decisionType()).isEqualTo(DecisionType.PASS);

        assertThat(result.pacingRate().value()).isCloseTo(0.6, within(0.000000001));
    }

    @Test
    void 활성_캠페인이어도_샘플_비율이_조정된_통과율보다_크면_BLOCK한다() {
        PacingPolicyResolver resolver = new PacingPolicyResolver(
                new EvenPacingPolicy(),
                new PeakWeightedPacingPolicy(
                        new TrafficWeight(0.5, 1.5)
                ),
                new AsapPacingPolicy()
        );

        PacingEngine engine = new PacingEngine(
                new GapBasedPacingRateCalculator(0.5),
                resolver
        );

        Instant now = Instant.parse("2026-07-21T06:00:00Z");

        Campaign campaign = new Campaign(
                "campaign-1",
                CampaignStatus.ACTIVE,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                PacingStrategy.EVEN
        );

        BudgetState budgetState = new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(20_000),
                Money.zero()
        );

        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.5),
                TrafficPeriod.NORMAL
        );

        PacingDecision result = engine.decide(
                campaign,
                budgetState,
                context,
                new Rate(0.7),
                now
        );

        assertThat(result.decisionType()).isEqualTo(DecisionType.BLOCK);
    }
}