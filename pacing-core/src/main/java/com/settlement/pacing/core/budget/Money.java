package com.settlement.pacing.core.budget;

import java.util.Objects;

public record Money(long amount) {
    public Money {
        if (amount < 0) throw new IllegalArgumentException("금액은 0 이상이어야 합니다");
    }

    public static Money zero() {
        return new Money(0);
    }

    public Money add(Money money) {
        Objects.requireNonNull(money, "더할 금액은 null일 수 없습니다");
        return new Money(Math.addExact(this.amount, money.amount));
    }

    public Money subtract(Money money) {
        Objects.requireNonNull(money, "뺄 금액은 null일 수 없습니다");

        if (this.amount < money.amount) {
            throw new IllegalArgumentException("금액 계산 결과는 음수가 될 수 없습니다");
        }

        return new Money(Math.subtractExact(this.amount, money.amount));
    }

    public boolean isLessThan(Money money) {
        Objects.requireNonNull(money, "비교할 금액은 null일 수 없습니다");
        return this.amount < money.amount;
    }

    public boolean isGreaterThanOrEqualTo(Money money) {
        Objects.requireNonNull(money, "비교할 금액은 null일 수 없습니다");
        return this.amount >= money.amount;
    }
}
