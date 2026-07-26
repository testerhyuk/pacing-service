package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface CampaignManagementGateway {

    Optional<CampaignSettings> findById(String campaignId);

    CampaignSettings save(
            CampaignSettings settings,
            LocalDate budgetDate
    );

    record CampaignSettings(
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
    }
}
