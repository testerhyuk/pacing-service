package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.core.budget.BudgetState;

import java.time.LocalDate;
import java.util.Optional;

public class RedisBudgetStateQueryAdapter
        implements BudgetStateQueryGateway {
    private final RedisBudgetStateStore budgetStateStore;
    private final BudgetStateRecoveryService recoveryService;

    public RedisBudgetStateQueryAdapter(
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService
    ) {
        this.budgetStateStore = budgetStateStore;
        this.recoveryService = recoveryService;
    }

    @Override
    public Optional<BudgetState> find(
            String campaignId,
            LocalDate budgetDate
    ) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        if (budgetDate == null) {
            throw new IllegalArgumentException(
                    "budgetDate는 null일 수 없습니다"
            );
        }

        RedisBudgetStateStore.ReadResult current =
                budgetStateStore.read(campaignId, budgetDate);

        if (current.found()) {
            return Optional.of(current.budgetState());
        }

        return recoveryService.recover(
                campaignId,
                budgetDate
        );
    }
}
