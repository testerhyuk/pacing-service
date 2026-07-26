package com.settlement.pacing.worker.reconciliation.application;

public interface ReservationRepairGateway {
    ReservationRepairResult repair(int batchSize);
}
