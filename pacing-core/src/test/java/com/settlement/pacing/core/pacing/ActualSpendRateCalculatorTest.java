package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class ActualSpendRateCalculatorTest {
    private final ActualSpendRateCalculator calculator =
            new ActualSpendRateCalculator();

    @Test
    void 전체_소진액과_전체_예약액을_포함해_실제_소진율을_계산한다() {
        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(1_000_000), // 전체 예산
                new Money(400_000),   // 전체 소진액
                new Money(100_000),   // 전체 예약액

                new Money(300_000),   // 일 예산 한도
                new Money(100_000),   // 오늘 소진액
                new Money(50_000)     // 오늘 예약액
        );

        Rate result = calculator.calculate(budgetState);

        assertThat(result.value()).isCloseTo(0.5, within(0.000000001));
    }

    @Test
    void 전체_예산을_모두_소진하면_실제_소진율은_100퍼센트다() {
        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                new Money(1_000_000),
                new Money(800_000),
                new Money(200_000),

                new Money(300_000),
                new Money(100_000),
                new Money(50_000)
        );

        Rate result = calculator.calculate(budgetState);

        assertThat(result).isEqualTo(Rate.full());
    }

    @Test
    void 전체_예산이_0이면_실제_소진율을_계산할_수_없다() {
        BudgetState budgetState = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),

                Money.zero(),
                Money.zero(),
                Money.zero(),

                new Money(300_000),
                Money.zero(),
                Money.zero()
        );

        assertThatThrownBy(() ->
                calculator.calculate(budgetState)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("전체 예산은 0보다 커야 합니다");
    }
}
