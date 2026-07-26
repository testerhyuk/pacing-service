package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "campaign")
public class CampaignEntity {
    @Id
    @Column(name = "campaign_id", nullable = false, length = 100)
    private String campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignStatus status;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "pacing_strategy", nullable = false, length = 30)
    private PacingStrategy pacingStrategy;

    @Column(name = "total_budget", nullable = false)
    private long totalBudget;

    @Column(name = "daily_budget_limit", nullable = false)
    private long dailyBudgetLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignEntity() {
    }

    public CampaignEntity(
            String campaignId,
            CampaignStatus status,
            Instant startAt,
            Instant endAt,
            PacingStrategy pacingStrategy,
            long totalBudget,
            long dailyBudgetLimit,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.campaignId = campaignId;
        this.status = status;
        this.startAt = startAt;
        this.endAt = endAt;
        this.pacingStrategy = pacingStrategy;
        this.totalBudget = totalBudget;
        this.dailyBudgetLimit = dailyBudgetLimit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public PacingStrategy getPacingStrategy() {
        return pacingStrategy;
    }

    public long getTotalBudget() {
        return totalBudget;
    }

    public long getDailyBudgetLimit() {
        return dailyBudgetLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
