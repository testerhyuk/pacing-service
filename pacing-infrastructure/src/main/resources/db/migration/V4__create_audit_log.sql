CREATE TABLE audit_log
(
    id           BIGSERIAL                NOT NULL,
    event_type   VARCHAR(50)              NOT NULL,
    client_id    VARCHAR(100),
    request_id   VARCHAR(100),
    target_id    VARCHAR(200),
    before_value TEXT,
    after_value  TEXT,
    result       VARCHAR(20)              NOT NULL,
    reason       VARCHAR(200),
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_audit_log
        PRIMARY KEY (id),

    CONSTRAINT ck_audit_log_event_type
        CHECK (event_type IN (
            'AUTHENTICATION_FAILURE',
            'AUTHORIZATION_FAILURE',
            'CAMPAIGN_CHANGE',
            'BUDGET_CHANGE',
            'PACING_STRATEGY_CHANGE',
            'HMAC_KEY_CHANGE',
            'CLIENT_PERMISSION_CHANGE'
        )),

    CONSTRAINT ck_audit_log_result
        CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_audit_log_occurred_at
    ON audit_log (occurred_at);

CREATE INDEX idx_audit_log_client_occurred_at
    ON audit_log (client_id, occurred_at);

CREATE INDEX idx_audit_log_event_type_occurred_at
    ON audit_log (event_type, occurred_at);
