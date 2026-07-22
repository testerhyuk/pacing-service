package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingResultTest {
    @Test
    void 판단_결과와_페이싱_상태를_함께_생성한다() {
        PacingDecision decision = PacingDecision.pass(new Rate(0.6));

        PacingState pacingState = new PacingState(
                new Rate(0.6),
                Instant.parse("2026-07-21T10:00:05Z")
        );

        PacingResult result = new PacingResult(decision, pacingState);

        assertThat(result.decision()).isEqualTo(decision);
        assertThat(result.pacingState()).isEqualTo(pacingState);
    }

    @Test
    void 판단_결과가_null이면_생성할_수_없다() {
        PacingState pacingState = new PacingState(
                new Rate(0.6),
                Instant.parse("2026-07-21T10:00:05Z")
        );

        assertThatThrownBy(() ->
                new PacingResult(null, pacingState)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("페이싱 판단 결과와 페이싱 상태는 null일 수 없습니다");
    }

    @Test
    void 페이싱_상태가_null이면_생성할_수_없다() {
        PacingDecision decision = PacingDecision.pass(new Rate(0.6));

        assertThatThrownBy(() ->
                new PacingResult(decision, null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("페이싱 판단 결과와 페이싱 상태는 null일 수 없습니다");
    }
}