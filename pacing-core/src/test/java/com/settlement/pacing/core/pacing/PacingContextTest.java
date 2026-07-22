package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PacingContextTest {
    @Test
    void 실제_소진율이_목표_소진율보다_낮으면_언더_페이싱이다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.4),
                new Rate(0.7)
        );

        assertThat(context.isUnderPaced()).isTrue();
    }

    @Test
    void 실제_소진율과_목표_소진율이_같으면_언더_페이싱이_아니다() {
        PacingContext context = new PacingContext(
                new Rate(0.6),
                new Rate(0.6),
                new Rate(0.7)
        );

        assertThat(context.isUnderPaced()).isFalse();
    }
}