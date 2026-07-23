package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.campaign.Campaign;

import java.util.Optional;

public interface CampaignQueryGateway {
    Optional<Campaign> findById(String campaignId);
}
