package com.settlement.pacing.core.pacing;

import java.time.Instant;

/**
 * 캠페인 광고 후보에 대한 페이싱 판단 요청이다.
 */
public record PacingRequest(
        String requestId,
        String campaignId,
        Instant requestedAt
) {
    public PacingRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId는 null이거나 비어있을 수 없습니다");
        }

        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId는 null이거나 비어있을 수 없습니다");
        }

        if (requestedAt == null) {
            throw new IllegalArgumentException("요청 시각은 null일 수 없습니다");
        }
    }
}