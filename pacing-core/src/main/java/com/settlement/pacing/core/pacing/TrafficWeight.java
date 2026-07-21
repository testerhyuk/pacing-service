package com.settlement.pacing.core.pacing;

public record TrafficWeight(
        /* 일반 시간대에 적용할 가중치 */
        double normalWeight,
        /* 피크 시간대에 적용할 가중치 */
        double peakWeight
) {
    public TrafficWeight {
        if (!Double.isFinite(normalWeight) || !Double.isFinite(peakWeight)) {
            throw new IllegalArgumentException("트래픽 가중치는 유한한 숫자여야 합니다");
        }

        if (normalWeight <= 0 || peakWeight <= 0) {
            throw new IllegalArgumentException("트래픽 가중치는 0보다 작을 수 없습니다");
        }

        if (peakWeight <= normalWeight) {
            throw new IllegalArgumentException("피크 시간대 가중치가 일반 시간대 가중치보다 작을 수 없습니다");
        }
    }

    // 현재 시간대에 맞는 가중치를 반환하는 메서드
    public double weightFor(TrafficPeriod trafficPeriod) {
        if (trafficPeriod == null) {
            throw new IllegalArgumentException("트래픽 시간대는 null일 수 없습니다");
        }

        return trafficPeriod == TrafficPeriod.PEAK ? peakWeight : normalWeight;
    }

    // 기본 페이싱 비율에 가중치를 적용
    public Rate applyTo(Rate baseRate, TrafficPeriod trafficPeriod) {
        if (baseRate == null) {
            throw new IllegalArgumentException("기본 페이싱 비율은 null일 수 없습니다");
        }

        double weightedRate = baseRate.value() * weightFor(trafficPeriod);

        return new Rate(Math.min(weightedRate, 1.0));
    }
}
