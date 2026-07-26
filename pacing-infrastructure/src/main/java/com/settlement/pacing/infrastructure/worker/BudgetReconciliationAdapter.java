package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryQueryRepository;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationGateway;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationResult;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class BudgetReconciliationAdapter
        implements BudgetReconciliationGateway {
    private final BudgetStateRecoveryQueryRepository queryRepository;
    private final RedisBudgetStateStore budgetStateStore;
    private final BudgetStateRecoveryService recoveryService;
    private final BudgetReconciliationRepository repository;
    private final Clock clock;

    public BudgetReconciliationAdapter(
            BudgetStateRecoveryQueryRepository queryRepository,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            BudgetReconciliationRepository repository,
            Clock clock
    ) {
        this.queryRepository = queryRepository;
        this.budgetStateStore = budgetStateStore;
        this.recoveryService = recoveryService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public BudgetReconciliationResult reconcile(
            LocalDate budgetDate,
            int batchSize
    ) {
        if (budgetDate == null || batchSize <= 0) {
            throw new IllegalArgumentException(
                    "예산 대사 기준일과 batchSize가 올바르지 않습니다"
            );
        }

        int checked = 0;
        int matched = 0;
        int mismatched = 0;
        int unavailable = 0;
        long mismatchTotal = 0L;

        String lastCampaignId = "";
        while (true) {
            List<String> campaignIds =
                    queryRepository.findCampaignIdsAfter(
                            lastCampaignId,
                            batchSize
                    );

            if (campaignIds.isEmpty()) {
                break;
            }

            for (String campaignId : campaignIds) {
                checked++;
                BudgetStateRecoveryQueryRepository.BudgetAggregate
                        ledger = queryRepository.aggregate(
                        campaignId,
                        budgetDate
                );
                Optional<BudgetState> redisState =
                        currentOrRecovered(
                                campaignId,
                                budgetDate
                        );

                if (redisState.isEmpty()) {
                    unavailable++;
                    continue;
                }

                BudgetState redis = redisState.get();
                long mismatchAmount = 0L;
                mismatchAmount = Math.addExact(
                        mismatchAmount,
                        absoluteDifference(
                                ledger.totalSpentAmount(),
                                redis.totalSpentAmount().amount()
                        )
                );
                mismatchAmount = Math.addExact(
                        mismatchAmount,
                        absoluteDifference(
                                ledger.totalReservedAmount(),
                                redis.totalReservedAmount().amount()
                        )
                );
                mismatchAmount = Math.addExact(
                        mismatchAmount,
                        absoluteDifference(
                                ledger.dailySpentAmount(),
                                redis.dailySpentAmount().amount()
                        )
                );
                mismatchAmount = Math.addExact(
                        mismatchAmount,
                        absoluteDifference(
                                ledger.dailyReservedAmount(),
                                redis.dailyReservedAmount().amount()
                        )
                );
                String status = mismatchAmount == 0L
                        ? "MATCHED"
                        : "MISMATCHED";

                repository.save(
                        campaignId,
                        budgetDate,
                        ledger.totalSpentAmount(),
                        ledger.totalReservedAmount(),
                        redis.totalSpentAmount().amount(),
                        redis.totalReservedAmount().amount(),
                        ledger.dailySpentAmount(),
                        ledger.dailyReservedAmount(),
                        redis.dailySpentAmount().amount(),
                        redis.dailyReservedAmount().amount(),
                        mismatchAmount,
                        status,
                        clock.instant()
                );

                if (mismatchAmount == 0L) {
                    matched++;
                } else {
                    mismatched++;
                    mismatchTotal = Math.addExact(
                            mismatchTotal,
                            mismatchAmount
                    );
                }
            }

            lastCampaignId = campaignIds.get(
                    campaignIds.size() - 1
            );
        }

        return new BudgetReconciliationResult(
                checked,
                matched,
                mismatched,
                unavailable,
                mismatchTotal
        );
    }

    private Optional<BudgetState> currentOrRecovered(
            String campaignId,
            LocalDate budgetDate
    ) {
        RedisBudgetStateStore.ReadResult current =
                budgetStateStore.read(campaignId, budgetDate);

        if (current.found()) {
            return Optional.of(current.budgetState());
        }

        return recoveryService.recover(campaignId, budgetDate);
    }

    private long absoluteDifference(long first, long second) {
        return first >= second
                ? Math.subtractExact(first, second)
                : Math.subtractExact(second, first);
    }
}
