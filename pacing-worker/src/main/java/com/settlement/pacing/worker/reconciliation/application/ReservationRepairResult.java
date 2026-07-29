package com.settlement.pacing.worker.reconciliation.application;

public record ReservationRepairResult(
        int scanned,
        int repaired,
        int alreadyPersisted,
        int removed,
        int failed
) {
}
