package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeakTimeWindowTest {
    private PeakTimeWindow peakTimeWindow;

    @BeforeEach
    void setUp() {
        peakTimeWindow = new PeakTimeWindow(
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                ZoneId.of("Asia/Seoul")
        );
    }

    @Test
    void 피크_시작_시각은_피크_시간에_포함된다() {
        // 한국 시각 18:00
        Instant instant = Instant.parse("2026-07-21T09:00:00Z");

        assertThat(peakTimeWindow.contains(instant)).isTrue();
    }

    @Test
    void 피크_시간_이전은_피크_시간에_포함되지_않는다() {
        // 한국 시각 17:59
        Instant instant = Instant.parse("2026-07-21T08:59:00Z");

        assertThat(peakTimeWindow.contains(instant)).isFalse();
    }

    @Test
    void 피크_시간_중간은_피크_시간에_포함된다() {
        // 한국 시각 20:00
        Instant instant = Instant.parse("2026-07-21T11:00:00Z");

        assertThat(peakTimeWindow.contains(instant)).isTrue();
    }

    @Test
    void 피크_종료_시각은_피크_시간에_포함되지_않는다() {
        // 한국 시각 23:00
        Instant instant = Instant.parse("2026-07-21T14:00:00Z");

        assertThat(peakTimeWindow.contains(instant)).isFalse();
    }

    @Test
    void 피크_시작_시각이_종료_시각보다_늦으면_생성할_수_없다() {
        assertThatThrownBy(() -> new PeakTimeWindow(
                LocalTime.of(23, 0),
                LocalTime.of(18, 0),
                ZoneId.of("Asia/Seoul")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}