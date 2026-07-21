package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PacingDecisionTest {
    @Test
    void PASS_결정은_통과로_판단한다() {
        PacingDecision decision = PacingDecision.pass(new Rate(0.7));

        assertThat(decision.isPass()).isTrue();
    }

    @Test
    void BLOCK_결정은_통과로_판단하지_않는다() {
        PacingDecision decision = PacingDecision.block();

        assertThat(decision.isPass()).isFalse();
    }
}