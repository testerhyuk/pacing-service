package com.settlement.pacing.api.error;

public class PacingStateUpdateException extends RuntimeException {
    public PacingStateUpdateException(String campaignId) {
        super("페이싱 상태 갱신에 실패했습니다: " + campaignId);
    }
}
