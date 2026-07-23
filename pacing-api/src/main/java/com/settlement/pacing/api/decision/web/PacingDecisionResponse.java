package com.settlement.pacing.api.decision.web;

import com.settlement.pacing.api.decision.application.PacingDecisionResult;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;

import java.time.Instant;

public record PacingDecisionResponse(
        String requestId,
        String campaignId,
        DecisionType decision,
        DecisionReason reason,
        double pacingRate,
        Instant decidedAt
) {
    public static PacingDecisionResponse from(PacingDecisionResult result) {
        return new PacingDecisionResponse(
                result.requestId(),
                result.campaignId(),
                result.decision(),
                result.reason(),
                result.pacingRate(),
                result.decidedAt()
        );
    }
}
