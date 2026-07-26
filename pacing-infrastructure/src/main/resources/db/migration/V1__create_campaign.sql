CREATE TABLE campaign
(
    campaign_id        VARCHAR(100)             NOT NULL,
    status             VARCHAR(20)              NOT NULL,
    start_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    pacing_strategy    VARCHAR(30)              NOT NULL,
    total_budget       BIGINT                   NOT NULL,
    daily_budget_limit BIGINT                   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_campaign
        PRIMARY KEY (campaign_id),

    CONSTRAINT ck_campaign_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),

    CONSTRAINT ck_campaign_pacing_strategy
        CHECK (pacing_strategy IN ('EVEN', 'PEAK_WEIGHTED', 'ASAP')),

    CONSTRAINT ck_campaign_period
        CHECK (start_at < end_at),

    CONSTRAINT ck_campaign_total_budget
        CHECK (total_budget >= 0),

    CONSTRAINT ck_campaign_daily_budget_limit
        CHECK (daily_budget_limit >= 0),

    CONSTRAINT ck_campaign_daily_budget_not_greater_than_total
        CHECK (daily_budget_limit <= total_budget)
);

CREATE INDEX idx_campaign_status_period
    ON campaign (status, start_at, end_at);