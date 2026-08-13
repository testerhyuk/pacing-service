package com.settlement.pacing.infrastructure.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface BillingEventJpaRepository
        extends JpaRepository<BillingEventEntity, String> {

    Optional<BillingEventEntity> findByReservationIdAndEventSequence(
            String reservationId,
            long eventSequence
    );

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO billing_event (
                event_id,
                reservation_id,
                event_type,
                target_applied_amount,
                event_sequence,
                occurred_at,
                processing_status,
                created_at,
                updated_at
            )
            VALUES (
                :eventId,
                :reservationId,
                :eventType,
                :targetAppliedAmount,
                :eventSequence,
                :occurredAt,
                'RECEIVED',
                :now,
                :now
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("reservationId") String reservationId,
            @Param("eventType") String eventType,
            @Param("targetAppliedAmount") long targetAppliedAmount,
            @Param("eventSequence") long eventSequence,
            @Param("occurredAt") Instant occurredAt,
            @Param("now") Instant now
    );

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE billing_event
            SET processing_status = 'COMPLETED',
                result_status = :resultStatus,
                result_applied_amount = :appliedAmount,
                reservation_version = :reservationVersion,
                total_overage_amount = :totalOverageAmount,
                daily_overage_amount = :dailyOverageAmount,
                failure_reason = NULL,
                processed_at = :now,
                updated_at = :now
            WHERE event_id = :eventId
            """, nativeQuery = true)
    int markCompleted(
            @Param("eventId") String eventId,
            @Param("resultStatus") String resultStatus,
            @Param("appliedAmount") long appliedAmount,
            @Param("reservationVersion") long reservationVersion,
            @Param("totalOverageAmount") long totalOverageAmount,
            @Param("dailyOverageAmount") long dailyOverageAmount,
            @Param("now") Instant now
    );

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE billing_event
            SET processing_status = 'DEAD_LETTER',
                failure_reason = :reason,
                processed_at = :now,
                updated_at = :now
            WHERE event_id = :eventId
              AND processing_status <> 'COMPLETED'
            """, nativeQuery = true)
    int markDeadLetter(
            @Param("eventId") String eventId,
            @Param("reason") String reason,
            @Param("now") Instant now
    );
}
