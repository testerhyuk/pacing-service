package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.core.budget.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "budget_reservation")
public class BudgetReservationEntity {
    @Id
    @Column(name = "reservation_id", nullable = false, length = 100)
    private String reservationId;

    @Column(name = "campaign_id", nullable = false, length = 100)
    private String campaignId;

    @Column(name = "budget_date", nullable = false)
    private LocalDate budgetDate;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "applied_amount", nullable = false)
    private long appliedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "last_billing_sequence", nullable = false)
    private long lastBillingSequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BudgetReservationEntity() {
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public LocalDate getBudgetDate() {
        return budgetDate;
    }

    public long getAmount() {
        return amount;
    }

    public long getAppliedAmount() {
        return appliedAmount;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getVersion() {
        return version;
    }

    public long getLastBillingSequence() {
        return lastBillingSequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
