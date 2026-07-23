package com.settlement.pacing.api.decision.application;

import com.settlement.pacing.api.error.InvalidRequestException;

import java.time.Instant;

public record PacingDecisionCommand(
        String requestId,
        String campaignId,
        Instant requestedAt
) {
    public PacingDecisionCommand {
        validateIdentifier(requestId, "requestId");
        validateIdentifier(campaignId, "campaignId");

        if (requestedAt == null) {
            throw new InvalidRequestException(
                    "requestedAt은 null일 수 없습니다"
            );
        }
    }

    private static void validateIdentifier(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(
                    fieldName + "는 null이거나 비어있을 수 없습니다"
            );
        }

        if (value.length() > 100) {
            throw new InvalidRequestException(
                    fieldName + "는 100자를 초과할 수 없습니다"
            );
        }
    }
}
