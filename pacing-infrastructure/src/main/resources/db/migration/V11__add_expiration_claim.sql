ALTER TABLE budget_reservation
    ADD COLUMN expiration_claim_token VARCHAR(100),
    ADD COLUMN expiration_claimed_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE budget_reservation
    ADD CONSTRAINT ck_budget_reservation_expiration_claim
        CHECK (
            (expiration_claim_token IS NULL
                AND expiration_claimed_until IS NULL)
            OR
            (expiration_claim_token IS NOT NULL
                AND expiration_claimed_until IS NOT NULL)
        );

CREATE INDEX idx_budget_reservation_expiration_claim
    ON budget_reservation (
        status,
        expires_at,
        expiration_claimed_until
    );
