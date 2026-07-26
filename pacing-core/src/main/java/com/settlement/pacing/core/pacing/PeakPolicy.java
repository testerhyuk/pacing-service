package com.settlement.pacing.core.pacing;

public record PeakPolicy(
        PeakTimeWindow timeWindow,
        TrafficWeight trafficWeight
) {
    public PeakPolicy {
        if (timeWindow == null || trafficWeight == null) {
            throw new IllegalArgumentException(
                    "피크 정책 값은 null일 수 없습니다"
            );
        }
    }
}
