package com.settlement.pacing.core.budget;

public enum ReservationStatus {
    RESERVED,   // 예산을 확보했지만 아직 과금되지 않음
    CONFIRMED,  // 실제 과금이 확정됨
    CANCELLED,  // 노출 실패 또는 취소로 예약이 해제됨
    EXPIRED     // 만료 시각까지 확정되지 않아 예약이 해제됨
}
