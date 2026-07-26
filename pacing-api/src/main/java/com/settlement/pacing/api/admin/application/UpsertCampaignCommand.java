package com.settlement.pacing.api.admin.application;

import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;

import java.time.Instant;

public record UpsertCampaignCommand(
        String campaignId,
        CampaignStatus status,
        Instant startAt,
        Instant endAt,
        PacingStrategy pacingStrategy,
        long totalBudget,
        long dailyBudgetLimit
) {
}
