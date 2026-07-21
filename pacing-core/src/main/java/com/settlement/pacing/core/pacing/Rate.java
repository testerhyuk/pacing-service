package com.settlement.pacing.core.pacing;

import java.util.Objects;

public record Rate(double value) {
    public Rate {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("비율 값은 유한한 숫자여야 합니다");
        }

        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("비율 값은 0.0 이상 1.0 이하여야 합니다");
        }
    }

    public static Rate zero() {
        return new Rate(0.0);
    }

    public static Rate full() {
        return new Rate(1.0);
    }

    public boolean isLessThan(Rate rate) {
        Objects.requireNonNull(rate, "비교할 비율은 null일 수 없습니다");
        return this.value < rate.value;
    }
}
