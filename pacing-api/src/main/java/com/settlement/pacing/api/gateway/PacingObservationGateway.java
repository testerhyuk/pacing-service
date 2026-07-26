package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PacingObservation;

import java.time.Instant;

public interface PacingObservationGateway {
    PacingObservation recent(
            String campaignId,
            Instant observedAt
    );

    boolean recordDecision(
            String requestId,
            String campaignId,
            DecisionType decisionType,
            Instant decidedAt
    );

    boolean recordReservation(
            String reservationId,
            String campaignId,
            Money amount,
            Instant reservedAt
    );
}
