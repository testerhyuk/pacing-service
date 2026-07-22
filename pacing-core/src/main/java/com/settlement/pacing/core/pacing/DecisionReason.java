package com.settlement.pacing.core.pacing;

public enum DecisionReason {
    PASS,                       // 페이싱 판단 통과
    CAMPAIGN_INACTIVE,          // 캠페인이 ACTIVE 상태가 아님
    OUTSIDE_CAMPAIGN_PERIOD,    // 캠페인 집행 기간이 아님
    BUDGET_EXHAUSTED,           // 전체 또는 일일 사용 가능 예산이 없음
    PACING_REJECTED             // 페이싱 비율에 따라 차단
}
