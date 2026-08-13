package com.settlement.pacing.infrastructure.worker;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public class ExpirationClaimRepository {
    private final JdbcClient jdbcClient;

    public ExpirationClaimRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<String> claim(
            Instant now,
            int batchSize,
            String token,
            Instant claimedUntil
    ) {
        return jdbcClient.sql("""
                        WITH candidates AS (
                            SELECT reservation_id
                            FROM budget_reservation
                            WHERE status = 'RESERVED'
                              AND expires_at <= :now
                              AND (
                                  expiration_claimed_until IS NULL
                                  OR expiration_claimed_until <= :now
                              )
                            ORDER BY expires_at, reservation_id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batchSize
                        )
                        UPDATE budget_reservation AS reservation
                        SET expiration_claim_token = :token,
                            expiration_claimed_until = :claimedUntil
                        FROM candidates
                        WHERE reservation.reservation_id =
                              candidates.reservation_id
                        RETURNING reservation.reservation_id
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("batchSize", batchSize)
                .param("token", token)
                .param(
                        "claimedUntil",
                        claimedUntil.atOffset(ZoneOffset.UTC)
                )
                .query(String.class)
                .list();
    }

    public void release(
            String reservationId,
            String token
    ) {
        jdbcClient.sql("""
                        UPDATE budget_reservation
                        SET expiration_claim_token = NULL,
                            expiration_claimed_until = NULL
                        WHERE reservation_id = :reservationId
                          AND expiration_claim_token = :token
                        """)
                .param("reservationId", reservationId)
                .param("token", token)
                .update();
    }
}
