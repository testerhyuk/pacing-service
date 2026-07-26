package com.settlement.pacing.infrastructure.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    @Query(value = """
            SELECT reservation_id
            FROM budget_reservation
            WHERE status = 'RESERVED'
              AND expires_at <= :now
            ORDER BY expires_at, reservation_id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<String> findExpirationCandidates(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    /**
     * Redis에서 더 높은 version으로 완료된 상태만 PostgreSQL에 반영한다.
     * 늦게 끝난 이전 작업이 최신 예약 상태를 덮어쓰지 못하게 한다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE budget_reservation
            SET status = :status,
                applied_amount = :appliedAmount,
                version = :version,
                updated_at = :updatedAt
            WHERE reservation_id = :reservationId
              AND version < :version
            """, nativeQuery = true)
    int updateFromRedis(
            @Param("reservationId") String reservationId,
            @Param("status") String status,
            @Param("appliedAmount") long appliedAmount,
            @Param("version") long version,
            @Param("updatedAt") Instant updatedAt
    );
}
