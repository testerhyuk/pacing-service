package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PacingObservationTest {
    private static final double EWMA_ALPHA = 0.5;

    @Test
    void 최근_구간이_0이면_EWMA도_감소한다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(0L, 0L, 0L, 0L)
                )
        );

        assertThat(
                observation.estimatedDecisionCountPerInterval(EWMA_ALPHA)
        ).isCloseTo(
                250.0,
                within(0.000_001)
        );
    }

    @Test
    void EWMA_기반으로_구간별_트래픽을_추정한다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(500L, 500L, 50L, 5_000L)
                )
        );

        assertThat(
                observation.estimatedDecisionCountPerInterval(EWMA_ALPHA)
        ).isCloseTo(
                300.0,
                within(0.000_001)
        );
    }

    @Test
    void 트래픽이_계속_일정하면_EWMA도_일정하다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(100)
                        )
                );

        double result =
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA);

        assertThat(result).isEqualTo(100.0);
    }

    @Test
    void 트래픽이_감소하면_EWMA도_최근_트래픽을_따라_감소한다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(500),
                                interval(500),
                                interval(500),
                                interval(100),
                                interval(100),
                                interval(100)
                        )
                );

        double result =
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA);

        /*
         * EWMA:
         * 500
         * 500
         * 500
         * 300
         * 200
         * 150
         */
        assertThat(result).isEqualTo(150.0);
    }

    @Test
    void 가장_최근_트래픽이_증가하면_평균보다_높게_반영된다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(100),
                                interval(500)
                        )
                );

        double ewma =
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA);

        double simpleAverage =
                (100 + 100 + 100 + 100 + 100 + 500)
                        / 6.0;

        assertThat(ewma)
                .isGreaterThan(simpleAverage);
    }

    @Test
    void 가장_최근_트래픽이_감소하면_단순_평균보다_낮게_반영된다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(500),
                                interval(500),
                                interval(500),
                                interval(100),
                                interval(100),
                                interval(100)
                        )
                );

        double ewma =
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA);

        double simpleAverage =
                (500 + 500 + 500 + 100 + 100 + 100)
                        / 6.0;

        assertThat(ewma)
                .isLessThan(simpleAverage);
    }

    @Test
    void observation이_없으면_0을_반환한다() {
        PacingObservation observation =
                PacingObservation.empty();

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isZero();
    }

    @Test
    void decisionCount가_없으면_0을_반환한다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(0),
                                interval(0),
                                interval(0)
                        )
                );

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isZero();
    }

    @Test
    void passCount가_없으면_0을_반환한다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                new PacingObservation.Interval(
                                        100,
                                        0,
                                        0,
                                        new Money(0)
                                )
                        )
                );

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isZero();
    }

    @Test
    void reservationAmount가_없으면_0을_반환한다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                new PacingObservation.Interval(
                                        100,
                                        100,
                                        100,
                                        new Money(0)
                                )
                        )
                );

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isZero();
    }

    @Test
    void 기존_집계용_accessor는_전체_interval을_합산한다() {
        PacingObservation observation =
                new PacingObservation(
                        List.of(
                                interval(100),
                                interval(200),
                                interval(300)
                        )
                );

        assertThat(observation.intervalCount())
                .isEqualTo(3);

        assertThat(observation.decisionCount())
                .isEqualTo(600);

        assertThat(observation.passCount())
                .isEqualTo(600);

        assertThat(observation.reservationCount())
                .isEqualTo(600);

        assertThat(observation.reservedAmount().amount())
                .isEqualTo(600);
    }

    private static PacingObservation.Interval interval(
            long decisionCount
    ) {
        return new PacingObservation.Interval(
                decisionCount,
                decisionCount,
                decisionCount,
                new Money(decisionCount)
        );
    }

    @Test
    void 최근_요청과_PASS당_예약액으로_구간별_100퍼센트_PASS_금액을_예측한다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(1_000L, 200L, 40L, 40_000L),
                        interval(1_000L, 200L, 40L, 40_000L)
                )
        );

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isCloseTo(
                200_000.0,
                within(0.000_001)
        );
    }

    @Test
    void 최근_구간에_갑자기_트래픽이_증가하면_EWMA가_기존_단순평균보다_최근값에_가까워진다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(100L, 100L, 10L, 1_000L),
                        interval(500L, 500L, 50L, 5_000L)
                )
        );

        // alpha=0.5 기준 EWMA: 100 -> 100 -> ... -> 300
        // PASS당 예약액은 10이므로 300 * 10 = 3,000
        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isCloseTo(
                3_000.0,
                within(0.000_001)
        );
    }

    @Test
    void 최근_트래픽이_0이_된_구간도_EWMA에_반영되어_기존_트래픽을_서서히_잊는다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(500L, 500L, 50L, 5_000L),
                        interval(0L, 0L, 0L, 0L)
                )
        );

        // alpha=0.5 기준 EWMA: 500 -> ... -> 250
        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isCloseTo(
                2_500.0,
                within(0.000_001)
        );
    }

    @Test
    void PASS나_예약이_없으면_예상_금액은_0이다() {
        PacingObservation observation = new PacingObservation(
                List.of(
                        interval(2_000L, 0L, 0L, 0L),
                        interval(0L, 0L, 0L, 0L)
                )
        );

        assertThat(
                observation.estimatedFullPassAmountPerInterval(EWMA_ALPHA)
        ).isZero();
    }

    @Test
    void PASS_건수가_판단_건수보다_많을_수_없다() {
        assertThatThrownBy(() ->
                new PacingObservation(
                        List.of(interval(10L, 11L, 0L, 0L))
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private PacingObservation.Interval interval(
            long decisionCount,
            long passCount,
            long reservationCount,
            long reservedAmount
    ) {
        return new PacingObservation.Interval(
                decisionCount,
                passCount,
                reservationCount,
                new Money(reservedAmount)
        );
    }
}
