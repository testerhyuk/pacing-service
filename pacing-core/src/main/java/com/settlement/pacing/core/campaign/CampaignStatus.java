package com.settlement.pacing.core.campaign;

public enum CampaignStatus {
    ACTIVE, // 현재 광고 집행 가능
    PAUSED, // 운영자 또는 광고주가 일시 중지
    ENDED // 캠페인이 종료되어 다시 집행하지 않음
}
