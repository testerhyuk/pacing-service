package com.settlement.pacing.core.campaign;

public enum PacingStrategy {
    EVEN, // 기간 전체에 예산을 고르게 집행
    PEAK_WEIGHTED, // 특정 피크 시간대에 더 많이 집행
    ASAP // 가능한 빠르게 예산 집행
}
