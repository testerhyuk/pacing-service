package com.settlement.pacing.api.decision.web;

import com.settlement.pacing.api.decision.application.PacingDecisionCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PacingDecisionRequest(
        @NotBlank @Size(min = 1, max = 100) String requestId,
        @NotBlank @Size(min = 1, max = 100) String campaignId,
        @NotNull Instant requestedAt
) {
    public PacingDecisionCommand toCommand() {
        return new PacingDecisionCommand(requestId, campaignId, requestedAt);
    }
}
