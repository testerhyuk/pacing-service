package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvenPacingPolicyTest {
    @Test
    void 샘플_비율이_페이싱_비율보다_낮으면_통과한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7),
                TrafficPeriod.NORMAL
        );

        EvenPacingPolicy policy = new EvenPacingPolicy();

        PacingDecision decision = policy.decide(context, new Rate(0.4));

        assertThat(decision.isPass()).isTrue();
    }

    @Test
    void 샘플_비율이_페이싱_비율보다_크면_차단한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7),
                TrafficPeriod.NORMAL
        );

        EvenPacingPolicy policy = new EvenPacingPolicy();

        PacingDecision decision = policy.decide(context, new Rate(0.8));

        assertThat(decision.isPass()).isFalse();
    }

    @Test
    void 샘플_비율이_페이싱_비율과_같으면_차단한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7),
                TrafficPeriod.NORMAL
        );

        EvenPacingPolicy policy = new EvenPacingPolicy();

        PacingDecision decision = policy.decide(context, new Rate(0.7));

        assertThat(decision.isPass()).isFalse();
    }
}