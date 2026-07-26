package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PacingObservationTest {
    @Test
    void 최근_요청과_PASS당_예약액으로_구간별_100퍼센트_PASS_금액을_예측한다() {
        PacingObservation observation = new PacingObservation(
                2,
                2_000L,
                400L,
                80L,
                new Money(80_000L)
        );

        assertThat(
                observation.estimatedFullPassAmountPerInterval()
        ).isCloseTo(
                200_000.0,
                within(0.000_001)
        );
    }

    @Test
    void PASS나_예약이_없으면_예상_금액은_0이다() {
        PacingObservation observation = new PacingObservation(
                2,
                2_000L,
                0L,
                0L,
                Money.zero()
        );

        assertThat(
                observation.estimatedFullPassAmountPerInterval()
        ).isZero();
    }

    @Test
    void PASS_건수가_판단_건수보다_많을_수_없다() {
        assertThatThrownBy(() -> new PacingObservation(
                1,
                10L,
                11L,
                0L,
                Money.zero()
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
