package com.settlement.pacing.core.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetStateTest {
    private BudgetState budgetState;

    @BeforeEach
    void setUp() {
        budgetState = new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(1_000_000),
                new Money(400_000),
                new Money(100_000)
        );
    }

    @Test
    void 전체_예산에서_소진액과_예약액을_제외한_사용_가능_금액_계산() {
        Money availableAmount = budgetState.availableAmount();

        assertThat(availableAmount).isEqualTo(new Money(500_000));
    }

    @Test
    void 실제_소진액과_예약액을_합산한_유효_소진액_계산() {
        Money effectiveAmount = budgetState.effectiveSpend();

        assertThat(effectiveAmount).isEqualTo(new Money(500_000));
    }

    @Test
    void 사용_가능_금액과_같은_금액은_예약할_수_있다() {
        Money availableAmount = budgetState.availableAmount();

        assertThat(budgetState.canReserve(availableAmount)).isTrue();
    }

    @Test
    void 사용_가능_금액보다_큰_금액은_예약할_수_없다() {
        Money availableAmountOver = budgetState.availableAmount().add(new Money(100_000));

        assertThat(budgetState.canReserve(availableAmountOver)).isFalse();
    }

    @Test
    void 전체_예산을_초과한_예산_상태는_생성할_수_없다() {
        assertThatThrownBy(() -> new BudgetState(
                LocalDate.of(2026, 7, 21),
                new Money(1_000_000),
                new Money(900_000),
                new Money(200_000)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("예약 금액과 사용된 금액이 총 금액을 초과할 수 없습니다");
    }
}