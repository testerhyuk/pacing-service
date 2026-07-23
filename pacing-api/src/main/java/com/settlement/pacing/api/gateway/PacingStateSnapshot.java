package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.pacing.PacingState;

public record PacingStateSnapshot(
        PacingState pacingState,
        long version
) {
    public PacingStateSnapshot {
        if (pacingState == null) {
            throw new IllegalArgumentException("PacingState는 null일 수 없습니다");
        }

        if (version < 0) {
            throw new IllegalArgumentException("version은 0 이상이어야 합니다");
        }
    }
}
