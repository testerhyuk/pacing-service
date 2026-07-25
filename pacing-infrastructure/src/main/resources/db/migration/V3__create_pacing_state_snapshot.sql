CREATE TABLE pacing_state_snapshot
(
    campaign_id      VARCHAR(100)             NOT NULL,
    pacing_rate      DOUBLE PRECISION         NOT NULL,
    state_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    persisted_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_pacing_state_snapshot
        PRIMARY KEY (campaign_id),

    CONSTRAINT fk_pacing_state_snapshot_campaign
        FOREIGN KEY (campaign_id)
            REFERENCES campaign (campaign_id),

    CONSTRAINT ck_pacing_state_snapshot_rate
        CHECK (pacing_rate >= 0.0 AND pacing_rate <= 1.0),

    CONSTRAINT ck_pacing_state_snapshot_version
        CHECK (version >= 0)
);
