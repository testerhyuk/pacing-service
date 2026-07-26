package com.settlement.pacing.infrastructure.pacing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pacing_state_snapshot")
public class PacingStateSnapshotEntity {
    @Id
    @Column(name = "campaign_id", nullable = false, length = 100)
    private String campaignId;

    @Column(name = "pacing_rate", nullable = false)
    private double pacingRate;

    @Column(name = "state_updated_at", nullable = false)
    private Instant stateUpdatedAt;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "persisted_at", nullable = false)
    private Instant persistedAt;

    protected PacingStateSnapshotEntity() {
    }

    public String getCampaignId() {
        return campaignId;
    }

    public double getPacingRate() {
        return pacingRate;
    }

    public Instant getStateUpdatedAt() {
        return stateUpdatedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getPersistedAt() {
        return persistedAt;
    }
}
