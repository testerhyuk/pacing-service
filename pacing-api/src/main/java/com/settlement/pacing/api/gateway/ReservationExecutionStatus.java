package com.settlement.pacing.api.gateway;

public enum ReservationExecutionStatus {
    CREATED,
    ALREADY_EXISTS,
    INSUFFICIENT_BUDGET,
    CONFLICT,
    BUDGET_STATE_NOT_FOUND
}
