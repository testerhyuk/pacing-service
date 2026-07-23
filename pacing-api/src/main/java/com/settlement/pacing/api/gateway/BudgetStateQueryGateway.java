package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.budget.BudgetState;

import java.time.LocalDate;
import java.util.Optional;

public interface BudgetStateQueryGateway {
    Optional<BudgetState> find(String campaignId, LocalDate budgetDate);
}
