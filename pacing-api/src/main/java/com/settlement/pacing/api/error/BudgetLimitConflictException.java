package com.settlement.pacing.api.error;

public class BudgetLimitConflictException
        extends RuntimeException {

    public BudgetLimitConflictException(String message) {
        super(message);
    }
}
