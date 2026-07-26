ALTER TABLE budget_reconciliation
    ADD COLUMN ledger_total_spent_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN ledger_total_reserved_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN redis_total_spent_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN redis_total_reserved_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE budget_reconciliation
    ADD CONSTRAINT ck_budget_reconciliation_total_amounts
        CHECK (
            ledger_total_spent_amount >= 0
            AND ledger_total_reserved_amount >= 0
            AND redis_total_spent_amount >= 0
            AND redis_total_reserved_amount >= 0
        );
