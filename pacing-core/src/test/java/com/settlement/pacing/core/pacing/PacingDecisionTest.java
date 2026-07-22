package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingDecisionTest {
    @Test
    void PASS_결정은_통과로_판단한다() {
        PacingDecision decision = PacingDecision.pass(new Rate(0.7));

        assertThat(decision.isPass()).isTrue();
        assertThat(decision.reason())
                .isEqualTo(DecisionReason.PASS);
    }

    @Test
    void BLOCK_결정은_통과로_판단하지_않는다() {
        PacingDecision decision = PacingDecision.block(new Rate(0.7));

        assertThat(decision.isPass()).isFalse();
        assertThat(decision.reason())
                .isEqualTo(DecisionReason.PACING_REJECTED);
    }

    @Test
    void BLOCK_결정도_적용된_페이싱_비율을_유지한다() {
        PacingDecision decision =
                PacingDecision.block(new Rate(0.7));

        assertThat(decision.pacingRate())
                .isEqualTo(new Rate(0.7));
    }

    @Test
    void 구체적인_BLOCK_사유를_지정할_수_있다() {
        PacingDecision decision = PacingDecision.block(
                DecisionReason.BUDGET_EXHAUSTED,
                new Rate(0.7)
        );

        assertThat(decision.decisionType())
                .isEqualTo(DecisionType.BLOCK);
        assertThat(decision.reason())
                .isEqualTo(DecisionReason.BUDGET_EXHAUSTED);
    }

    @Test
    void PASS_결정에_PASS가_아닌_사유를_사용할_수_없다() {
        assertThatThrownBy(() ->
                new PacingDecision(
                        DecisionType.PASS,
                        DecisionReason.PACING_REJECTED,
                        new Rate(0.7)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PASS 결정의 사유는 PASS여야 합니다");
    }

    @Test
    void BLOCK_결정에_PASS_사유를_사용할_수_없다() {
        assertThatThrownBy(() ->
                new PacingDecision(
                        DecisionType.BLOCK,
                        DecisionReason.PASS,
                        new Rate(0.7)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BLOCK 결정의 사유는 PASS일 수 없습니다");
    }
}
