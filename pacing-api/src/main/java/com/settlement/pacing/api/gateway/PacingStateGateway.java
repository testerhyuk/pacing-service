package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.pacing.PacingState;

import java.util.Optional;

public interface PacingStateGateway {
    PacingStateSnapshot getOrInitialize(
            String campaignId,
            PacingState initialState
    );

    Optional<PacingStateSnapshot> findByCampaignId(String campaignId);

    boolean compareAndSet(
            String campaignId,
            long expectedVersion,
            PacingState newState
    );
}
