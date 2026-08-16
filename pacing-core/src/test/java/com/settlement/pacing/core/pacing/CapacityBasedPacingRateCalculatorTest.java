package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CapacityBasedPacingRateCalculatorTest {
    private static final double EWMA_ALPHA = 0.5;

    private final CapacityBasedPacingRateCalculator calculator =
            new CapacityBasedPacingRateCalculator(
                    20L,
                    1.0,
                    1.0,
                    0.1
            );

    @Test
    void 다음_구간_필요_금액을_100퍼센트_PASS_예상액으로_나눈다() {
        Rate result = calculator.calculate(
                new Rate(0.10),
                budgetState(
                        1_000_000L,
                        300_000L,
                        1_000_000L,
                        300_000L
                ),
                new Rate(0.50),
                new Rate(0.52),
                observation(
                        1_000L,
                        400L,
                        80L,
                        80_000L
                ),
                EWMA_ALPHA
        );

        // 남은 70만 원의 4%인 2만 8천 원을,
        // 100% PASS 예상액 20만 원으로 나눈다.
        assertThat(result.value()).isCloseTo(
                0.14,
                within(0.000_001)
        );
    }

    @Test
    void 일일_사용_가능액보다_큰_구간_목표는_일일_한도로_제한한다() {
        Rate result = calculator.calculate(
                new Rate(0.10),
                budgetState(
                        1_000_000L,
                        300_000L,
                        310_000L,
                        300_000L
                ),
                new Rate(0.50),
                new Rate(0.52),
                observation(
                        1_000L,
                        400L,
                        80L,
                        80_000L
                ),
                EWMA_ALPHA
        );

        // 오늘 남은 1만 원 ÷ 100% PASS 예상액 20만 원
        assertThat(result.value()).isCloseTo(
                0.05,
                within(0.000_001)
        );
    }

    @Test
    void 표본이_부족하면_현재_비율을_유지한다() {
        Rate currentRate = new Rate(0.25);

        Rate result = calculator.calculate(
                currentRate,
                budgetState(
                        1_000_000L,
                        300_000L,
                        1_000_000L,
                        300_000L
                ),
                new Rate(0.50),
                new Rate(0.52),
                observation(
                        100L,
                        10L,
                        1L,
                        1_000L
                ),
                EWMA_ALPHA
        );

        assertThat(result).isEqualTo(currentRate);
    }

    @Test
    void 충분히_PASS했지만_예약이_없으면_탐색을_위해_비율을_올린다() {
        Rate result = calculator.calculate(
                new Rate(0.20),
                budgetState(
                        1_000_000L,
                        300_000L,
                        1_000_000L,
                        300_000L
                ),
                new Rate(0.50),
                new Rate(0.52),
                observation(
                        1_000L,
                        100L,
                        0L,
                        0L
                ),
                EWMA_ALPHA
        );

        assertThat(result.value()).isCloseTo(
                0.30,
                within(0.000_001)
        );
    }

    @Test
    void 계산된_비율은_평활화하고_최대_변경_폭으로_제한한다() {
        CapacityBasedPacingRateCalculator boundedCalculator =
                new CapacityBasedPacingRateCalculator(
                        20L,
                        0.5,
                        0.2,
                        0.1
                );

        Rate result = boundedCalculator.calculate(
                new Rate(0.10),
                budgetState(
                        1_000_000L,
                        0L,
                        1_000_000L,
                        0L
                ),
                new Rate(0.50),
                new Rate(0.60),
                observation(
                        100L,
                        100L,
                        10L,
                        1_000L
                ),
                EWMA_ALPHA
        );

        assertThat(result.value()).isCloseTo(
                0.30,
                within(0.000_001)
        );
    }

    @Test
    void ASAP_목표는_모든_요청을_PASS하도록_계산한다() {
        Rate result = calculator.calculate(
                new Rate(0.20),
                budgetState(
                        1_000_000L,
                        300_000L,
                        1_000_000L,
                        300_000L
                ),
                Rate.full(),
                Rate.full(),
                PacingObservation.empty(),
                EWMA_ALPHA
        );

        assertThat(result).isEqualTo(Rate.full());
    }

    private PacingObservation observation(
            long decisionCount,
            long passCount,
            long reservationCount,
            long reservedAmount
    ) {
        return new PacingObservation(
                java.util.List.of(
                        new PacingObservation.Interval(
                                decisionCount,
                                passCount,
                                reservationCount,
                                new Money(reservedAmount)
                        )
                )
        );
    }

    private BudgetState budgetState(
            long totalBudget,
            long totalSpent,
            long dailyLimit,
            long dailySpent
    ) {
        return new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 26),
                new Money(totalBudget),
                new Money(totalSpent),
                Money.zero(),
                new Money(dailyLimit),
                new Money(dailySpent),
                Money.zero()
        );
    }
}
