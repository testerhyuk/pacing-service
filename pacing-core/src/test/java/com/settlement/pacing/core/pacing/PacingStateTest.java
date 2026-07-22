package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacingStateTest {
    private PacingState pacingState;
    private Duration updateInterval;

    @BeforeEach
    void setUp() {
        pacingState = new PacingState(
                new Rate(0.5),
                Instant.parse("2026-07-21T10:00:00Z")
        );

        updateInterval = Duration.ofSeconds(5);
    }

    @Test
    void 갱신_주기가_지나기_전에는_비율을_갱신하지_않는다() {
        Instant now =
                Instant.parse("2026-07-21T10:00:04Z");

        assertThat(
                pacingState.shouldUpdateAt(now, updateInterval)
        ).isFalse();
    }

    @Test
    void 갱신_주기와_같은_시각에는_비율을_갱신한다() {
        Instant now =
                Instant.parse("2026-07-21T10:00:05Z");

        assertThat(
                pacingState.shouldUpdateAt(now, updateInterval)
        ).isTrue();
    }

    @Test
    void 갱신_주기가_지난_후에는_비율을_갱신한다() {
        Instant now =
                Instant.parse("2026-07-21T10:00:06Z");

        assertThat(
                pacingState.shouldUpdateAt(now, updateInterval)
        ).isTrue();
    }

    @Test
    void 갱신_주기가_0이면_판단할_수_없다() {
        assertThatThrownBy(() ->
                pacingState.shouldUpdateAt(
                        Instant.parse("2026-07-21T10:00:05Z"),
                        Duration.ZERO
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("갱신 주기는 0보다 커야 합니다");
    }

    @Test
    void 갱신_주기가_음수이면_판단할_수_없다() {
        assertThatThrownBy(() ->
                pacingState.shouldUpdateAt(
                        Instant.parse("2026-07-21T10:00:05Z"),
                        Duration.ofSeconds(-1)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("갱신 주기는 0보다 커야 합니다");
    }
}