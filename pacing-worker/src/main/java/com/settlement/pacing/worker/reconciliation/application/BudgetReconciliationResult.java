package com.settlement.pacing.worker.reconciliation.application;

public record BudgetReconciliationResult(
        int checked,
        int matched,
        int mismatched,
        int unavailable,
        long mismatchAmount,
        int repaired,
        int versionConflicts
) {
}
