package com.settlement.pacing.api.decision.application;

import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PacingRequest;
import com.settlement.pacing.core.pacing.PacingResult;

import java.time.Instant;

public record PacingDecisionResult(
        String requestId,
        String campaignId,
        DecisionType decision,
        DecisionReason reason,
        double pacingRate,
        Instant decidedAt
) {
    public static PacingDecisionResult from(PacingRequest request, PacingResult pacingResult, Instant decidedAt) {
        return new PacingDecisionResult(
                request.requestId(),
                request.campaignId(),
                pacingResult.decision().decisionType(),
                pacingResult.decision().reason(),
                pacingResult.decision().pacingRate().value(),
                decidedAt
        );
    }
}
