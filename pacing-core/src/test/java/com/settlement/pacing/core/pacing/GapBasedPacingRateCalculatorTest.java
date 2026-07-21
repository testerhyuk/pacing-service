package com.settlement.pacing.core.pacing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GapBasedPacingRateCalculatorTest {
    private final GapBasedPacingRateCalculator calculator = new GapBasedPacingRateCalculator(0.5);

    @Test
    void 실제_소진율이_목표보다_낮으면_통과율이_증가한다() {
        Rate result = calculator.calculate(
                new Rate(0.5),
                new Rate(0.6),
                new Rate(0.4)
        );

        assertThat(result.value())
                .isCloseTo(0.6, within(0.000000001));
    }

    @Test
    void 실제_소진율이_목표보다_높으면_통과율이_감소한다() {
        Rate result = calculator.calculate(
                new Rate(0.5),
                new Rate(0.4),
                new Rate(0.6)
        );

        /*
         * 0.5 + (0.4 - 0.6) × 0.5
         * = 0.4
         */
        assertThat(result.value())
                .isCloseTo(0.4, within(0.000000001));
    }

    @Test
    void 목표와_실제_소진율이_같으면_현재_통과율을_유지한다() {
        Rate result = calculator.calculate(
                new Rate(0.5),
                new Rate(0.6),
                new Rate(0.6)
        );

        assertThat(result.value())
                .isCloseTo(0.5, within(0.000000001));
    }

    @Test
    void 계산된_통과율이_1을_초과하면_1로_제한한다() {
        Rate result = calculator.calculate(
                new Rate(0.9),
                new Rate(1.0),
                new Rate(0.0)
        );

        /*
         * 0.9 + (1.0 - 0.0) × 0.5
         * = 1.4
         * → 1.0으로 제한
         */
        assertThat(result).isEqualTo(Rate.full());
    }

    @Test
    void 계산된_통과율이_0보다_작으면_0으로_제한한다() {
        Rate result = calculator.calculate(
                new Rate(0.1),
                new Rate(0.0),
                new Rate(1.0)
        );

        /*
         * 0.1 + (0.0 - 1.0) × 0.5
         * = -0.4
         * → 0.0으로 제한
         */
        assertThat(result).isEqualTo(Rate.zero());
    }
}