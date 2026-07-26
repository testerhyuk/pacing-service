package com.settlement.pacing.worker.reconciliation.application;

import java.time.LocalDate;

public interface BudgetReconciliationGateway {
    BudgetReconciliationResult reconcile(
            LocalDate budgetDate,
            int batchSize
    );
}
