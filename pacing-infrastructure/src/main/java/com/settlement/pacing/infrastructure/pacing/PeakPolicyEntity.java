package com.settlement.pacing.infrastructure.pacing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "peak_policy")
public class PeakPolicyEntity {
    @Id
    @Column(name = "policy_id", nullable = false)
    private short policyId;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "zone_id", nullable = false, length = 100)
    private String zoneId;

    @Column(name = "normal_weight", nullable = false)
    private double normalWeight;

    @Column(name = "peak_weight", nullable = false)
    private double peakWeight;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PeakPolicyEntity() {
    }

    public PeakPolicyEntity(
            short policyId,
            LocalTime startTime,
            LocalTime endTime,
            String zoneId,
            double normalWeight,
            double peakWeight,
            Instant updatedAt
    ) {
        this.policyId = policyId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.zoneId = zoneId;
        this.normalWeight = normalWeight;
        this.peakWeight = peakWeight;
        this.updatedAt = updatedAt;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getZoneId() {
        return zoneId;
    }

    public double getNormalWeight() {
        return normalWeight;
    }

    public double getPeakWeight() {
        return peakWeight;
    }
}
