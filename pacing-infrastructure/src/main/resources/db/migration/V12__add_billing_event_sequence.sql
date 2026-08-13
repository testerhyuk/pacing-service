ALTER TABLE budget_reservation
    ADD COLUMN last_billing_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE budget_reservation
    ADD CONSTRAINT ck_budget_reservation_last_billing_sequence
        CHECK (last_billing_sequence >= 0);

ALTER TABLE billing_event
    RENAME COLUMN actual_amount TO target_applied_amount;

ALTER TABLE billing_event
    ADD COLUMN event_sequence BIGINT;

WITH ordered_events AS (
    SELECT event_id,
           ROW_NUMBER() OVER (
               PARTITION BY reservation_id
               ORDER BY occurred_at, event_id
           ) AS sequence
    FROM billing_event
)
UPDATE billing_event event
SET event_sequence = ordered_events.sequence
FROM ordered_events
WHERE event.event_id = ordered_events.event_id;

UPDATE billing_event
SET target_applied_amount = 0
WHERE event_type = 'CANCELLED';

ALTER TABLE billing_event
    ALTER COLUMN event_sequence SET NOT NULL;

ALTER TABLE billing_event
    ADD CONSTRAINT ck_billing_event_sequence
        CHECK (event_sequence > 0);

ALTER TABLE billing_event
    DROP CONSTRAINT ck_billing_event_actual_amount;

ALTER TABLE billing_event
    ADD CONSTRAINT ck_billing_event_target_applied_amount
        CHECK (
            target_applied_amount >= 0
            AND (
                event_type <> 'CHARGED'
                OR target_applied_amount > 0
            )
        );

CREATE UNIQUE INDEX uk_billing_event_reservation_sequence
    ON billing_event (reservation_id, event_sequence);

UPDATE budget_reservation reservation
SET last_billing_sequence = completed.last_sequence
FROM (
    SELECT reservation_id,
           MAX(event_sequence) AS last_sequence
    FROM billing_event
    WHERE processing_status = 'COMPLETED'
    GROUP BY reservation_id
) completed
WHERE reservation.reservation_id = completed.reservation_id;

DROP INDEX idx_billing_event_reservation_occurred;

CREATE INDEX idx_billing_event_reservation_sequence
    ON billing_event (reservation_id, event_sequence DESC);
