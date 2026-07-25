CREATE TABLE budget_reservation
(
    reservation_id VARCHAR(100)             NOT NULL,
    campaign_id    VARCHAR(100)             NOT NULL,
    budget_date    DATE                     NOT NULL,
    amount         BIGINT                   NOT NULL,
    applied_amount BIGINT                   NOT NULL DEFAULT 0,
    status         VARCHAR(20)              NOT NULL,
    reserved_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT                   NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_budget_reservation
        PRIMARY KEY (reservation_id),

    CONSTRAINT fk_budget_reservation_campaign
        FOREIGN KEY (campaign_id)
            REFERENCES campaign (campaign_id),

    CONSTRAINT ck_budget_reservation_amount
        CHECK (amount > 0),

    CONSTRAINT ck_budget_reservation_applied_amount
        CHECK (applied_amount >= 0),

    CONSTRAINT ck_budget_reservation_status
        CHECK (status IN (
            'RESERVED',
            'CONFIRMED',
            'CANCELLED',
            'EXPIRED'
        )),

    CONSTRAINT ck_budget_reservation_period
        CHECK (reserved_at < expires_at),

    CONSTRAINT ck_budget_reservation_version
        CHECK (version >= 0),

    CONSTRAINT ck_budget_reservation_applied_status
        CHECK (
            (status = 'CONFIRMED' AND applied_amount > 0)
            OR
            (status <> 'CONFIRMED' AND applied_amount = 0)
        )
);

CREATE INDEX idx_budget_reservation_campaign_status
    ON budget_reservation (campaign_id, status);

CREATE INDEX idx_budget_reservation_campaign_date_status
    ON budget_reservation (
        campaign_id,
        budget_date,
        status
    );

CREATE INDEX idx_budget_reservation_status_expires_at
    ON budget_reservation (status, expires_at);
