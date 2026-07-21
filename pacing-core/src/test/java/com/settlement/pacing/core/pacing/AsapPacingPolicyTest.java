package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsapPacingPolicyTest {
    @Test
    void ASAP은_샘플값과_관계없이_100퍼센트_통과한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.2),
                TrafficPeriod.NORMAL
        );

        AsapPacingPolicy policy = new AsapPacingPolicy();

        PacingDecision decision = policy.decide(
                context,
                new Rate(0.9)
        );

        assertThat(decision.isPass()).isTrue();
        assertThat(decision.pacingRate()).isEqualTo(Rate.full());
    }
}