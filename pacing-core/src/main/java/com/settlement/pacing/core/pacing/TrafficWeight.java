package com.settlement.pacing.core.pacing;

public record TrafficWeight(
        /* 일반 시간대에 적용할 가중치 */
        double normalWeight,
        /* 피크 시간대에 적용할 가중치 */
        double peakWeight
) {
    public TrafficWeight {
        if (!Double.isFinite(normalWeight) || !Double.isFinite(peakWeight)) {
            throw new IllegalArgumentException("트래픽 가중치는 0보다 커야 합니다");
        }

        if (normalWeight <= 0 || peakWeight <= 0) {
            throw new IllegalArgumentException("트래픽 가중치는 0보다 작을 수 없습니다");
        }

        if (peakWeight <= normalWeight) {
            throw new IllegalArgumentException("피크 시간대 가중치는 일반 시간대 가중치보다 커야 합니다");
        }
    }
}
