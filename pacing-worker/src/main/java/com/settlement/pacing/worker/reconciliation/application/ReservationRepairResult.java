package com.settlement.pacing.worker.reconciliation.application;

public record ReservationRepairResult(
        int scanned,
        int repaired,
        int removed,
        int failed
) {
}
