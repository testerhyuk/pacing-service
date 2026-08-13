package com.settlement.pacing.worker.reconciliation.application;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.Mockito.*;

class DailyBudgetReconciliationSchedulerTest {
    private static final Instant NOW =
            Instant.parse("2026-08-13T00:10:00Z");
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 8, 12);

    private final BudgetReconciliationGateway gateway =
            mock(BudgetReconciliationGateway.class);
    private final BudgetReconciliationLockGateway lockGateway =
            mock(BudgetReconciliationLockGateway.class);
    private final PacingWorkerProperties properties =
            mock(PacingWorkerProperties.class);
    private final PacingWorkerMetrics metrics =
            mock(PacingWorkerMetrics.class);

    @Test
    void Lock을_획득한_Worker만_일일_예산_대사를_실행한다() {
        BudgetReconciliationLockGateway.LockHandle lock =
                new BudgetReconciliationLockGateway.LockHandle(
                        BUDGET_DATE,
                        "lock-token"
                );
        BudgetReconciliationResult result =
                new BudgetReconciliationResult(
                        1,
                        1,
                        0,
                        0,
                        0L,
                        0,
                        0
                );

        when(properties.reconciliation()).thenReturn(configuration());
        when(lockGateway.tryAcquire(
                BUDGET_DATE,
                Duration.ofHours(2)
        )).thenReturn(Optional.of(lock));
        when(gateway.reconcile(BUDGET_DATE, 500))
                .thenReturn(result);

        scheduler().reconcilePreviousBudgetDate();

        verify(gateway).reconcile(BUDGET_DATE, 500);
        verify(metrics).recordBudgetReconciliation(result);
        verify(lockGateway).release(lock);
    }

    @Test
    void Lock을_획득하지_못한_Worker는_일일_예산_대사를_건너뛴다() {
        when(properties.reconciliation()).thenReturn(configuration());
        when(lockGateway.tryAcquire(
                BUDGET_DATE,
                Duration.ofHours(2)
        )).thenReturn(Optional.empty());

        scheduler().reconcilePreviousBudgetDate();

        verifyNoInteractions(gateway);
        verify(metrics).recordBudgetReconciliationSkipped();
        verify(lockGateway, never()).release(any());
    }

    @Test
    void Lock_저장소_오류가_발생하면_대사를_실행하지_않고_기록한다() {
        RuntimeException failure =
                new IllegalStateException("Redis unavailable");
        when(properties.reconciliation()).thenReturn(configuration());
        when(lockGateway.tryAcquire(
                BUDGET_DATE,
                Duration.ofHours(2)
        )).thenThrow(failure);

        scheduler().reconcilePreviousBudgetDate();

        verifyNoInteractions(gateway);
        verify(metrics).recordBudgetReconciliationLockFailure(failure);
    }

    private DailyBudgetReconciliationScheduler scheduler() {
        return new DailyBudgetReconciliationScheduler(
                gateway,
                lockGateway,
                properties,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PacingWorkerProperties.Reconciliation configuration() {
        return new PacingWorkerProperties.Reconciliation(
                "0 10 0 * * *",
                ZoneId.of("UTC"),
                500,
                3,
                Duration.ofHours(2)
        );
    }
}
