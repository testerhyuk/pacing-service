package com.settlement.pacing.infrastructure.worker;

public enum BillingEventProcessingState {
    RECEIVED,
    COMPLETED,
    DEAD_LETTER
}
