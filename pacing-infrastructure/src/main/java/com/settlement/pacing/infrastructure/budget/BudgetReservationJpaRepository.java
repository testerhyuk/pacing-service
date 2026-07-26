package com.settlement.pacing.infrastructure.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

public interface BudgetReservationJpaRepository
        extends JpaRepository<BudgetReservationEntity, String> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO budget_reservation (
                reservation_id,
                campaign_id,
                budget_date,
                amount,
                applied_amount,
                status,
                reserved_at,
                expires_at,
                version,
                created_at,
                updated_at
            )
            VALUES (
                :reservationId,
                :campaignId,
                :budgetDate,
                :amount,
                0,
                'RESERVED',
                :reservedAt,
                :expiresAt,
                0,
                :persistedAt,
                :persistedAt
            )
            ON CONFLICT (reservation_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("reservationId") String reservationId,
            @Param("campaignId") String campaignId,
            @Param("budgetDate") LocalDate budgetDate,
            @Param("amount") long amount,
            @Param("reservedAt") Instant reservedAt,
            @Param("expiresAt") Instant expiresAt,
            @Param("persistedAt") Instant persistedAt
    );
}
