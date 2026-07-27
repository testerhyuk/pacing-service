package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.campaign.Campaign;

public record DecisionContextSnapshot(
        Campaign campaign,
        BudgetState budgetState,
        PacingStateSnapshot pacingStateSnapshot
) {
    public DecisionContextSnapshot {
        if (campaign == null) {
            throw new IllegalArgumentException(
                    "Campaign은 null일 수 없습니다"
            );
        }

        if (budgetState == null) {
            throw new IllegalArgumentException(
                    "BudgetState는 null일 수 없습니다"
            );
        }

        if (pacingStateSnapshot == null) {
            throw new IllegalArgumentException(
                    "PacingStateSnapshot은 null일 수 없습니다"
            );
        }
    }
}