ALTER TABLE budget_reconciliation
    DROP CONSTRAINT ck_budget_reconciliation_status;

ALTER TABLE budget_reconciliation
    ADD CONSTRAINT ck_budget_reconciliation_status
        CHECK (status IN (
            'MATCHED',
            'MISMATCHED',
            'REPAIRED',
            'VERSION_CONFLICT'
        ));
