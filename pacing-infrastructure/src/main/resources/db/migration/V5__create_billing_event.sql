CREATE TABLE billing_event
(
    event_id              VARCHAR(100)             NOT NULL,
    reservation_id        VARCHAR(100)             NOT NULL,
    event_type            VARCHAR(20)              NOT NULL,
    actual_amount         BIGINT                   NOT NULL,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_status     VARCHAR(20)              NOT NULL,
    result_status         VARCHAR(20),
    result_applied_amount BIGINT,
    reservation_version   BIGINT,
    total_overage_amount  BIGINT,
    daily_overage_amount  BIGINT,
    failure_reason        VARCHAR(500),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at          TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_billing_event
        PRIMARY KEY (event_id),

    CONSTRAINT ck_billing_event_type
        CHECK (event_type IN (
            'CHARGED',
            'CANCELLED',
            'ADJUSTED'
        )),

    CONSTRAINT ck_billing_event_actual_amount
        CHECK (actual_amount > 0),

    CONSTRAINT ck_billing_event_processing_status
        CHECK (processing_status IN (
            'RECEIVED',
            'COMPLETED',
            'DEAD_LETTER'
        )),

    CONSTRAINT ck_billing_event_result_status
        CHECK (
            result_status IS NULL
            OR result_status IN (
                'RESERVED',
                'CONFIRMED',
                'CANCELLED',
                'EXPIRED'
            )
        ),

    CONSTRAINT ck_billing_event_result_amount
        CHECK (
            result_applied_amount IS NULL
            OR result_applied_amount >= 0
        ),

    CONSTRAINT ck_billing_event_reservation_version
        CHECK (
            reservation_version IS NULL
            OR reservation_version >= 0
        ),

    CONSTRAINT ck_billing_event_overage
        CHECK (
            (total_overage_amount IS NULL
             OR total_overage_amount >= 0)
            AND
            (daily_overage_amount IS NULL
             OR daily_overage_amount >= 0)
        ),

    CONSTRAINT ck_billing_event_completed_fields
        CHECK (
            processing_status <> 'COMPLETED'
            OR (
                result_status IS NOT NULL
                AND result_applied_amount IS NOT NULL
                AND reservation_version IS NOT NULL
                AND total_overage_amount IS NOT NULL
                AND daily_overage_amount IS NOT NULL
                AND processed_at IS NOT NULL
            )
        )
);

CREATE INDEX idx_billing_event_reservation_occurred
    ON billing_event (reservation_id, occurred_at);

CREATE INDEX idx_billing_event_status_created
    ON billing_event (processing_status, created_at);
