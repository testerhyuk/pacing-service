package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
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
    private final int maxRepairAttempts;

    public BudgetReconciliationAdapter(
            BudgetStateRecoveryQueryRepository queryRepository,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            BudgetReconciliationRepository repository,
            Clock clock,
            int maxRepairAttempts
    ) {
        if (maxRepairAttempts <= 0) {
            throw new IllegalArgumentException(
                    "예산 대사 최대 보정 시도 횟수는 0보다 커야 합니다"
            );
        }

        this.queryRepository = queryRepository;
        this.budgetStateStore = budgetStateStore;
        this.recoveryService = recoveryService;
        this.repository = repository;
        this.clock = clock;
        this.maxRepairAttempts = maxRepairAttempts;
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
        int repaired = 0;
        int versionConflicts = 0;
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
                CampaignResult campaignResult = reconcileCampaign(
                        campaignId,
                        budgetDate
                );

                switch (campaignResult.status()) {
                    case MATCHED -> matched++;
                    case REPAIRED -> {
                        mismatched++;
                        repaired++;
                    }
                    case VERSION_CONFLICT -> {
                        mismatched++;
                        versionConflicts++;
                    }
                    case UNAVAILABLE -> unavailable++;
                }

                if (campaignResult.mismatchAmount() > 0L) {
                    mismatchTotal = Math.addExact(
                            mismatchTotal,
                            campaignResult.mismatchAmount()
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
                mismatchTotal,
                repaired,
                versionConflicts
        );
    }

    private CampaignResult reconcileCampaign(
            String campaignId,
            LocalDate budgetDate
    ) {
        for (int attempt = 1;
             attempt <= maxRepairAttempts;
             attempt++) {
            Optional<RedisBudgetStateStore.ReadResult> currentResult =
                    currentOrRecovered(campaignId, budgetDate);

            if (currentResult.isEmpty()) {
                return CampaignResult.unavailable();
            }

            RedisBudgetStateStore.ReadResult current =
                    currentResult.get();
            BudgetState redis = current.budgetState();
            BudgetStateRecoveryQueryRepository.BudgetAggregate ledger =
                    queryRepository.aggregate(
                            campaignId,
                            budgetDate
                    );
            long mismatchAmount = mismatchAmount(ledger, redis);

            if (mismatchAmount == 0L) {
                saveResult(
                        campaignId,
                        budgetDate,
                        ledger,
                        redis,
                        0L,
                        "MATCHED"
                );
                return CampaignResult.matched();
            }

            BudgetState repairedState = new BudgetState(
                    campaignId,
                    budgetDate,
                    redis.totalBudget(),
                    new Money(ledger.totalSpentAmount()),
                    new Money(ledger.totalReservedAmount()),
                    redis.dailyBudgetLimit(),
                    new Money(ledger.dailySpentAmount()),
                    new Money(ledger.dailyReservedAmount())
            );

            RedisBudgetStateStore.RepairResult repairResult =
                    budgetStateStore.repairIfVersionMatches(
                            repairedState,
                            current.totalVersion(),
                            current.dailyVersion()
                    );

            if (repairResult.status()
                    == RedisBudgetStateStore.RepairStatus.UPDATED) {
                saveResult(
                        campaignId,
                        budgetDate,
                        ledger,
                        redis,
                        mismatchAmount,
                        "REPAIRED"
                );
                return CampaignResult.repaired(mismatchAmount);
            }

            if (repairResult.status()
                    == RedisBudgetStateStore.RepairStatus.MISSING) {
                if (attempt == maxRepairAttempts) {
                    return CampaignResult.unavailable();
                }
                continue;
            }

            if (attempt == maxRepairAttempts) {
                saveResult(
                        campaignId,
                        budgetDate,
                        ledger,
                        redis,
                        mismatchAmount,
                        "VERSION_CONFLICT"
                );
                return CampaignResult.versionConflict(
                        mismatchAmount
                );
            }
        }

        throw new IllegalStateException(
                "예산 대사 보정 결과를 결정하지 못했습니다"
        );
    }

    private Optional<RedisBudgetStateStore.ReadResult>
    currentOrRecovered(
            String campaignId,
            LocalDate budgetDate
    ) {
        RedisBudgetStateStore.ReadResult current =
                budgetStateStore.read(campaignId, budgetDate);

        if (current.found()) {
            return Optional.of(current);
        }

        if (recoveryService.recover(
                campaignId,
                budgetDate
        ).isEmpty()) {
            return Optional.empty();
        }

        RedisBudgetStateStore.ReadResult recovered =
                budgetStateStore.read(campaignId, budgetDate);

        return recovered.found()
                ? Optional.of(recovered)
                : Optional.empty();
    }

    private long mismatchAmount(
            BudgetStateRecoveryQueryRepository.BudgetAggregate ledger,
            BudgetState redis
    ) {
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
        return Math.addExact(
                mismatchAmount,
                absoluteDifference(
                        ledger.dailyReservedAmount(),
                        redis.dailyReservedAmount().amount()
                )
        );
    }

    private void saveResult(
            String campaignId,
            LocalDate budgetDate,
            BudgetStateRecoveryQueryRepository.BudgetAggregate ledger,
            BudgetState redis,
            long mismatchAmount,
            String status
    ) {
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
    }

    private long absoluteDifference(long first, long second) {
        return first >= second
                ? Math.subtractExact(first, second)
                : Math.subtractExact(second, first);
    }

    private enum CampaignStatus {
        MATCHED,
        REPAIRED,
        VERSION_CONFLICT,
        UNAVAILABLE
    }

    private record CampaignResult(
            CampaignStatus status,
            long mismatchAmount
    ) {
        private static CampaignResult matched() {
            return new CampaignResult(CampaignStatus.MATCHED, 0L);
        }

        private static CampaignResult repaired(long mismatchAmount) {
            return new CampaignResult(
                    CampaignStatus.REPAIRED,
                    mismatchAmount
            );
        }

        private static CampaignResult versionConflict(
                long mismatchAmount
        ) {
            return new CampaignResult(
                    CampaignStatus.VERSION_CONFLICT,
                    mismatchAmount
            );
        }

        private static CampaignResult unavailable() {
            return new CampaignResult(
                    CampaignStatus.UNAVAILABLE,
                    0L
            );
        }
    }
}
