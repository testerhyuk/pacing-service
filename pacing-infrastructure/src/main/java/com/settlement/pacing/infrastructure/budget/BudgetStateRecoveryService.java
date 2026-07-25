package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.infrastructure.common.RedisRecoveryLock;
import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class BudgetStateRecoveryService {
    private final BudgetStateRecoveryQueryRepository queryRepository;
    private final RedisBudgetStateStore budgetStateStore;
    private final RedisRecoveryLock recoveryLock;
    private final RedisInfrastructureProperties properties;
    private final Clock clock;
    private final PacingInfrastructureMetrics metrics;

    public BudgetStateRecoveryService(
            BudgetStateRecoveryQueryRepository queryRepository,
            RedisBudgetStateStore budgetStateStore,
            RedisRecoveryLock recoveryLock,
            RedisInfrastructureProperties properties,
            Clock clock,
            PacingInfrastructureMetrics metrics
    ) {
        this.queryRepository = queryRepository;
        this.budgetStateStore = budgetStateStore;
        this.recoveryLock = recoveryLock;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Optional<BudgetState> recover(
            String campaignId,
            LocalDate budgetDate
    ) {
        Optional<String> lockToken =
                recoveryLock.tryAcquire(campaignId);

        if (lockToken.isEmpty()) {
            return waitForRecovery(campaignId, budgetDate);
        }

        try {
            return recoverAsLockOwner(
                    campaignId,
                    budgetDate
            );
        } finally {
            recoveryLock.release(
                    campaignId,
                    lockToken.get()
            );
        }
    }

    private Optional<BudgetState> recoverAsLockOwner(
            String campaignId,
            LocalDate budgetDate
    ) {
        Optional<BudgetStateRecoveryQueryRepository
                .CampaignBudgetPolicy> policy =
                queryRepository.findCampaignBudgetPolicy(
                        campaignId
                );

        if (policy.isEmpty()) {
            metrics.recordBudgetRecovery("CAMPAIGN_NOT_FOUND");
            return Optional.empty();
        }

        BudgetStateRecoveryQueryRepository.BudgetAggregate
                aggregate = queryRepository.aggregate(
                campaignId,
                budgetDate,
                clock.instant()
        );

        BudgetStateRecoveryQueryRepository.CampaignBudgetPolicy
                budgetPolicy = policy.get();

        BudgetState recovered = new BudgetState(
                campaignId,
                budgetDate,
                new Money(budgetPolicy.totalBudget()),
                new Money(aggregate.totalSpentAmount()),
                new Money(aggregate.totalReservedAmount()),
                new Money(budgetPolicy.dailyBudgetLimit()),
                new Money(aggregate.dailySpentAmount()),
                new Money(aggregate.dailyReservedAmount())
        );

        budgetStateStore.initializeIfAbsent(recovered);

        RedisBudgetStateStore.ReadResult stored =
                budgetStateStore.read(campaignId, budgetDate);

        if (!stored.found()) {
            throw new DataAccessResourceFailureException(
                    "Redis 예산 상태 복구 후에도 상태를 조회할 수 없습니다"
            );
        }

        metrics.recordBudgetRecovery("RECOVERED");
        return Optional.of(stored.budgetState());
    }

    private Optional<BudgetState> waitForRecovery(
            String campaignId,
            LocalDate budgetDate
    ) {
        long timeoutNanos =
                properties.recoveryWaitTimeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;

        while (System.nanoTime() < deadline) {
            RedisBudgetStateStore.ReadResult current =
                    budgetStateStore.read(
                            campaignId,
                            budgetDate
                    );

            if (current.found()) {
                metrics.recordBudgetRecovery("WAITED_FOR_OWNER");
                return Optional.of(current.budgetState());
            }

            sleepBeforeRetry();
        }

        throw new QueryTimeoutException(
                "Redis 예산 상태 복구 대기 시간을 초과했습니다"
        );
    }

    private void sleepBeforeRetry() {
        try {
            TimeUnit.NANOSECONDS.sleep(
                    properties.recoveryRetryInterval()
                            .toNanos()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QueryTimeoutException(
                    "Redis 예산 상태 복구 대기가 중단됐습니다",
                    exception
            );
        }
    }
}
