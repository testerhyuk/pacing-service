CREATE TABLE peak_policy
(
    policy_id      SMALLINT                 NOT NULL,
    start_time     TIME                     NOT NULL,
    end_time       TIME                     NOT NULL,
    zone_id        VARCHAR(100)             NOT NULL,
    normal_weight  DOUBLE PRECISION         NOT NULL,
    peak_weight    DOUBLE PRECISION         NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_peak_policy
        PRIMARY KEY (policy_id),

    CONSTRAINT ck_peak_policy_singleton
        CHECK (policy_id = 1),

    CONSTRAINT ck_peak_policy_time
        CHECK (start_time < end_time),

    CONSTRAINT ck_peak_policy_weights
        CHECK (
            normal_weight > 0
            AND peak_weight > normal_weight
        )
);

ALTER TABLE audit_log
    DROP CONSTRAINT ck_audit_log_event_type;

ALTER TABLE audit_log
    ADD CONSTRAINT ck_audit_log_event_type
        CHECK (event_type IN (
            'AUTHENTICATION_FAILURE',
            'AUTHORIZATION_FAILURE',
            'CAMPAIGN_CHANGE',
            'BUDGET_CHANGE',
            'PACING_STRATEGY_CHANGE',
            'PEAK_POLICY_CHANGE',
            'HMAC_KEY_CHANGE',
            'CLIENT_PERMISSION_CHANGE'
        ));
