package com.settlement.pacing.api.admin.web;

import com.settlement.pacing.api.admin.application.UpsertCampaignCommand;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CampaignUpsertRequest(
        @NotNull CampaignStatus status,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull PacingStrategy pacingStrategy,
        @Min(0) long totalBudget,
        @Min(0) long dailyBudgetLimit
) {
    public UpsertCampaignCommand toCommand(String campaignId) {
        return new UpsertCampaignCommand(
                campaignId,
                status,
                startAt,
                endAt,
                pacingStrategy,
                totalBudget,
                dailyBudgetLimit
        );
    }
}
