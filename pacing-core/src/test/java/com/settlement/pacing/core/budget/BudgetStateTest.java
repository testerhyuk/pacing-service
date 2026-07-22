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
        budgetState = createBudgetState("campaign-1");
    }

    @Test
    void 캠페인_전체_사용_가능_금액을_계산한다() {
        assertThat(budgetState.totalAvailableAmount())
                .isEqualTo(new Money(500_000));
    }

    @Test
    void 오늘_사용_가능_금액을_계산한다() {
        assertThat(budgetState.dailyAvailableAmount())
                .isEqualTo(new Money(150_000));
    }

    @Test
    void 전체와_오늘_사용_가능_금액_중_작은_금액을_반환한다() {
        assertThat(budgetState.availableAmount())
                .isEqualTo(new Money(150_000));
    }

    @Test
    void 캠페인_전체_유효_소진액을_계산한다() {
        assertThat(budgetState.totalEffectiveSpend())
                .isEqualTo(new Money(500_000));
    }

    @Test
    void 오늘_유효_소진액을_계산한다() {
        assertThat(budgetState.dailyEffectiveSpend())
                .isEqualTo(new Money(150_000));
    }

    @Test
    void 최종_사용_가능_금액과_같은_금액은_예약할_수_있다() {
        assertThat(budgetState.canReserve(new Money(150_000)))
                .isTrue();
    }

    @Test
    void 오늘_남은_한도를_초과하면_전체_예산이_남아도_예약할_수_없다() {
        assertThat(budgetState.canReserve(new Money(200_000)))
                .isFalse();
    }

    @Test
    void _0원은_예약할_수_없다() {
        assertThat(budgetState.canReserve(Money.zero()))
                .isFalse();
    }

    @Test
    void 예산을_예약하면_전체와_오늘_예약액이_증가한다() {
        BudgetState reserved = budgetState.reserve(new Money(100_000));

        assertThat(reserved.totalReservedAmount())
                .isEqualTo(new Money(200_000));
        assertThat(reserved.dailyReservedAmount())
                .isEqualTo(new Money(150_000));
    }

    @Test
    void 사용_가능_예산을_초과해서_예약할_수_없다() {
        assertThatThrownBy(() ->
                budgetState.reserve(new Money(150_001))
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("사용 가능한 예산이 부족합니다");
    }

    @Test
    void 과금을_확정하면_예약액이_감소하고_실제_과금액만큼_소진액이_증가한다() {
        BudgetState confirmed = budgetState.confirm(
                new Money(50_000),
                new Money(60_000)
        );

        assertThat(confirmed.totalSpentAmount())
                .isEqualTo(new Money(460_000));
        assertThat(confirmed.totalReservedAmount())
                .isEqualTo(new Money(50_000));
        assertThat(confirmed.dailySpentAmount())
                .isEqualTo(new Money(160_000));
        assertThat(confirmed.dailyReservedAmount())
                .isEqualTo(Money.zero());
    }

    @Test
    void 예약을_취소하거나_만료하면_전체와_오늘_예약액이_감소한다() {
        BudgetState released = budgetState.release(new Money(50_000));

        assertThat(released.totalReservedAmount())
                .isEqualTo(new Money(50_000));
        assertThat(released.dailyReservedAmount())
                .isEqualTo(Money.zero());
    }

    @Test
    void 만료_후_지연_과금을_추가하면_예약액은_유지하고_소진액만_증가한다() {
        BudgetState charged = budgetState.addSpent(new Money(250_000));

        assertThat(charged.totalSpentAmount())
                .isEqualTo(new Money(650_000));
        assertThat(charged.dailySpentAmount())
                .isEqualTo(new Money(350_000));
        assertThat(charged.totalReservedAmount())
                .isEqualTo(new Money(100_000));
        assertThat(charged.dailyReservedAmount())
                .isEqualTo(new Money(50_000));
        assertThat(charged.dailyAvailableAmount())
                .isEqualTo(Money.zero());
    }

    @Test
    void 확정_과금을_취소하면_전체와_오늘_소진액이_감소한다() {
        BudgetState cancelled =
                budgetState.subtractSpent(new Money(50_000));

        assertThat(cancelled.totalSpentAmount())
                .isEqualTo(new Money(350_000));
        assertThat(cancelled.dailySpentAmount())
                .isEqualTo(new Money(50_000));
    }

    @Test
    void 지연_과금으로_전체_예산을_초과한_상태도_생성할_수_있다() {
        BudgetState overSpent = new BudgetState(
                "campaign-1",
                LocalDate.of(2026, 7, 21),
                new Money(100_000),
                new Money(120_000),
                Money.zero(),
                new Money(100_000),
                new Money(120_000),
                Money.zero()
        );

        assertThat(overSpent.totalAvailableAmount())
                .isEqualTo(Money.zero());
        assertThat(overSpent.dailyAvailableAmount())
                .isEqualTo(Money.zero());
        assertThat(overSpent.canReserve(new Money(1)))
                .isFalse();
    }

    @Test
    void 금액_변경에_0원을_사용할_수_없다() {
        assertThatThrownBy(() -> budgetState.reserve(Money.zero()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리할 금액은 0보다 커야 합니다");
        assertThatThrownBy(() -> budgetState.confirm(new Money(1), Money.zero()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리할 금액은 0보다 커야 합니다");
        assertThatThrownBy(() -> budgetState.release(Money.zero()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리할 금액은 0보다 커야 합니다");
        assertThatThrownBy(() -> budgetState.addSpent(Money.zero()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리할 금액은 0보다 커야 합니다");
        assertThatThrownBy(() -> budgetState.subtractSpent(Money.zero()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리할 금액은 0보다 커야 합니다");
    }

    @Test
    void campaignId가_null이면_생성할_수_없다() {
        assertThatThrownBy(() -> createBudgetState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaignId는 null이거나 비어있을 수 없습니다");
    }

    @Test
    void campaignId가_비어있으면_생성할_수_없다() {
        assertThatThrownBy(() -> createBudgetState(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaignId는 null이거나 비어있을 수 없습니다");
    }

    private BudgetState createBudgetState(String campaignId) {
        return new BudgetState(
                campaignId,
                LocalDate.of(2026, 7, 21),

                new Money(1_000_000), // 전체 예산
                new Money(400_000),   // 전체 소진액
                new Money(100_000),   // 전체 예약액

                new Money(300_000),   // 일 예산 한도
                new Money(100_000),   // 오늘 소진액
                new Money(50_000)     // 오늘 예약액
        );
    }
}
