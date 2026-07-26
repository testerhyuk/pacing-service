package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.api.gateway.CampaignManagementGateway.CampaignSettings;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;

import java.time.Instant;

public record CampaignAdminResponse(
        String campaignId,
        CampaignStatus status,
        Instant startAt,
        Instant endAt,
        PacingStrategy pacingStrategy,
        long totalBudget,
        long dailyBudgetLimit,
        Instant createdAt,
        Instant updatedAt
) {
    public static CampaignAdminResponse from(
            CampaignSettings settings
    ) {
        return new CampaignAdminResponse(
                settings.campaignId(),
                settings.status(),
                settings.startAt(),
                settings.endAt(),
                settings.pacingStrategy(),
                settings.totalBudget(),
                settings.dailyBudgetLimit(),
                settings.createdAt(),
                settings.updatedAt()
        );
    }
}
