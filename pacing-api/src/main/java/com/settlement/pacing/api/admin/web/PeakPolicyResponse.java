package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.core.pacing.PeakPolicy;

import java.time.LocalTime;
import java.time.ZoneId;

public record PeakPolicyResponse(
        LocalTime startTime,
        LocalTime endTime,
        ZoneId zoneId,
        double normalWeight,
        double peakWeight
) {
    public static PeakPolicyResponse from(PeakPolicy policy) {
        return new PeakPolicyResponse(
                policy.timeWindow().startTime(),
                policy.timeWindow().endTime(),
                policy.timeWindow().zoneId(),
                policy.trafficWeight().normalWeight(),
                policy.trafficWeight().peakWeight()
        );
    }
}
