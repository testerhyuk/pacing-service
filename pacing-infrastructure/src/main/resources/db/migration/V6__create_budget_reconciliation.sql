CREATE TABLE budget_reconciliation
(
    id                      BIGSERIAL                NOT NULL,
    campaign_id             VARCHAR(100)             NOT NULL,
    budget_date             DATE                     NOT NULL,
    ledger_spent_amount     BIGINT                   NOT NULL,
    ledger_reserved_amount  BIGINT                   NOT NULL,
    redis_spent_amount      BIGINT                   NOT NULL,
    redis_reserved_amount   BIGINT                   NOT NULL,
    mismatch_amount         BIGINT                   NOT NULL,
    status                  VARCHAR(20)              NOT NULL,
    reconciled_at           TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_budget_reconciliation
        PRIMARY KEY (id),
    CONSTRAINT fk_budget_reconciliation_campaign
        FOREIGN KEY (campaign_id)
            REFERENCES campaign (campaign_id),
    CONSTRAINT ck_budget_reconciliation_amounts
        CHECK (
            ledger_spent_amount >= 0
            AND ledger_reserved_amount >= 0
            AND redis_spent_amount >= 0
            AND redis_reserved_amount >= 0
            AND mismatch_amount >= 0
        ),
    CONSTRAINT ck_budget_reconciliation_status
        CHECK (status IN ('MATCHED', 'MISMATCHED'))
);

CREATE INDEX idx_budget_reconciliation_date_status
    ON budget_reconciliation (budget_date, status);

CREATE INDEX idx_budget_reconciliation_campaign_date
    ON budget_reconciliation (campaign_id, budget_date);
