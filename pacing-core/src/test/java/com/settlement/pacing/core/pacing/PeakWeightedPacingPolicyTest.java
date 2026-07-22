package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeakWeightedPacingPolicyTest {
    private final PeakWeightedPacingPolicy policy =
            new PeakWeightedPacingPolicy();

    @Test
    void 샘플_비율이_계산된_페이싱_비율보다_낮으면_PASS한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7)
        );

        PacingDecision decision = policy.decide(
                context,
                new Rate(0.6)
        );

        assertThat(decision.isPass()).isTrue();
        assertThat(decision.pacingRate())
                .isEqualTo(new Rate(0.7));
    }

    @Test
    void 샘플_비율이_계산된_페이싱_비율보다_높으면_BLOCK한다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7)
        );

        PacingDecision decision = policy.decide(
                context,
                new Rate(0.8)
        );

        assertThat(decision.isPass()).isFalse();
        assertThat(decision.pacingRate())
                .isEqualTo(new Rate(0.7));
    }
}