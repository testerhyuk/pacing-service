package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.core.budget.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "billing_event")
public class BillingEventEntity {
    @Id
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "reservation_id", nullable = false, length = 100)
    private String reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private BillingEventType eventType;

    @Column(name = "target_applied_amount", nullable = false)
    private long targetAppliedAmount;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private BillingEventProcessingState processingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 20)
    private ReservationStatus resultStatus;

    @Column(name = "result_applied_amount")
    private Long resultAppliedAmount;

    @Column(name = "reservation_version")
    private Long reservationVersion;

    @Column(name = "total_overage_amount")
    private Long totalOverageAmount;

    @Column(name = "daily_overage_amount")
    private Long dailyOverageAmount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillingEventEntity() {
    }

    public String getEventId() {
        return eventId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public BillingEventType getEventType() {
        return eventType;
    }

    public long getTargetAppliedAmount() {
        return targetAppliedAmount;
    }

    public long getEventSequence() {
        return eventSequence;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BillingEventProcessingState getProcessingStatus() {
        return processingStatus;
    }

    public ReservationStatus getResultStatus() {
        return resultStatus;
    }

    public Long getResultAppliedAmount() {
        return resultAppliedAmount;
    }

    public Long getReservationVersion() {
        return reservationVersion;
    }

    public Long getTotalOverageAmount() {
        return totalOverageAmount;
    }

    public Long getDailyOverageAmount() {
        return dailyOverageAmount;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
