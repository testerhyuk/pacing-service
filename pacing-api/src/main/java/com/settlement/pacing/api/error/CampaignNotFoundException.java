package com.settlement.pacing.api.error;

public class CampaignNotFoundException extends RuntimeException {
    public CampaignNotFoundException(String campaignId) {
        super("캠페인을 찾을 수 없습니다: " + campaignId);
    }
}
