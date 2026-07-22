package com.settlement.pacing.core.billing;

public enum BillingEventType {
    CHARGED,    // 실제 과금 확정
    CANCELLED,  // 과금 또는 예약 취소
    ADJUSTED    // 기존 과금액 보정
}