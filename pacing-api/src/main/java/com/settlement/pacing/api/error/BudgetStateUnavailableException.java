package com.settlement.pacing.api.error;

public class BudgetStateUnavailableException extends RuntimeException {
    public BudgetStateUnavailableException() {
        super("예산 상태를 조회할 수 없습니다");
    }
}
