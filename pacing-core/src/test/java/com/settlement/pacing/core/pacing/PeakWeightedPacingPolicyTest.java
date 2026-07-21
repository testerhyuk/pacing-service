package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeakWeightedPacingPolicyTest {
    @Test
    void 피크_시간대에는_가중치가_적용된_비율로_통과한다() {
        TrafficWeight trafficWeight = new TrafficWeight(
                0.5,
                1.5
        );

        PeakWeightedPacingPolicy policy =
                new PeakWeightedPacingPolicy(trafficWeight);

        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.6),
                TrafficPeriod.PEAK
        );

        PacingDecision decision = policy.decide(
                context,
                new Rate(0.8)
        );

        assertThat(decision.isPass()).isTrue();
        assertThat(decision.pacingRate()).isEqualTo(new Rate(0.9));
    }

    @Test
    void 일반_시간대에는_낮은_가중치가_적용되어_차단한다() {
        TrafficWeight trafficWeight = new TrafficWeight(
                0.5,
                1.5
        );

        PeakWeightedPacingPolicy policy =
                new PeakWeightedPacingPolicy(trafficWeight);

        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.6),
                TrafficPeriod.NORMAL
        );

        PacingDecision decision = policy.decide(
                context,
                new Rate(0.4)
        );

        assertThat(decision.isPass()).isFalse();
    }

    @Test
    void 가중치_적용_결과가_1을_초과하면_100퍼센트로_제한한다() {
        TrafficWeight trafficWeight = new TrafficWeight(
                0.5,
                1.5
        );

        Rate result = trafficWeight.applyTo(
                new Rate(0.8),
                TrafficPeriod.PEAK
        );

        assertThat(result).isEqualTo(Rate.full());
    }
}