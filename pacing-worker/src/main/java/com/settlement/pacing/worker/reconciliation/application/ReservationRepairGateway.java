package com.settlement.pacing.worker.reconciliation.application;

import java.time.Instant;

public interface ReservationRepairGateway {
    ReservationRepairResult repair(
            int batchSize,
            Instant eligibleBefore
    );
}
