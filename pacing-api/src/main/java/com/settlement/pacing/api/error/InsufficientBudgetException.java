package com.settlement.pacing.api.error;

public class InsufficientBudgetException extends RuntimeException {
    public InsufficientBudgetException() {
        super("사용 가능한 예산이 부족합니다");
    }
}
