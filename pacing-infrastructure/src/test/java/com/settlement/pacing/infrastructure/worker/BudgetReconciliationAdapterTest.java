package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryQueryRepository;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BudgetReconciliationAdapterTest {
    private static final String CAMPAIGN_ID = "campaign-1";
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 8, 12);
    private static final Instant NOW =
            Instant.parse("2026-08-13T00:10:00Z");

    private final BudgetStateRecoveryQueryRepository queryRepository =
            mock(BudgetStateRecoveryQueryRepository.class);
    private final RedisBudgetStateStore budgetStateStore =
            mock(RedisBudgetStateStore.class);
    private final BudgetStateRecoveryService recoveryService =
            mock(BudgetStateRecoveryService.class);
    private final BudgetReconciliationRepository repository =
            mock(BudgetReconciliationRepository.class);

    @Test
    void 불일치한_예산은_조회한_버전이_같을_때_보정한다() {
        BudgetReconciliationAdapter adapter = adapter(3);
        RedisBudgetStateStore.ReadResult observed =
                readResult(3L, 7L);
        BudgetStateRecoveryQueryRepository.BudgetAggregate ledger =
                ledger();

        campaignPage();
        when(budgetStateStore.read(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(observed);
        when(queryRepository.aggregate(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(ledger);
        when(budgetStateStore.repairIfVersionMatches(
                any(BudgetState.class),
                eq(3L),
                eq(7L)
        )).thenReturn(new RedisBudgetStateStore.RepairResult(
                RedisBudgetStateStore.RepairStatus.UPDATED,
                4L,
                8L
        ));

        BudgetReconciliationResult result = adapter.reconcile(
                BUDGET_DATE,
                100
        );

        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.matched()).isZero();
        assertThat(result.mismatched()).isEqualTo(1);
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(result.versionConflicts()).isZero();
        assertThat(result.unavailable()).isZero();
        assertThat(result.mismatchAmount()).isEqualTo(60L);

        verify(repository).save(
                eq(CAMPAIGN_ID),
                eq(BUDGET_DATE),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                eq(60L),
                eq("REPAIRED"),
                eq(NOW)
        );
    }

    @Test
    void 버전_충돌이_반복되면_보정을_포기하고_기록한다() {
        BudgetReconciliationAdapter adapter = adapter(3);
        RedisBudgetStateStore.ReadResult observed =
                readResult(3L, 7L);

        campaignPage();
        when(budgetStateStore.read(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(observed);
        when(queryRepository.aggregate(CAMPAIGN_ID, BUDGET_DATE))
                .thenReturn(ledger());
        when(budgetStateStore.repairIfVersionMatches(
                any(BudgetState.class),
                anyLong(),
                anyLong()
        )).thenReturn(new RedisBudgetStateStore.RepairResult(
                RedisBudgetStateStore.RepairStatus.VERSION_MISMATCH,
                4L,
                8L
        ));

        BudgetReconciliationResult result = adapter.reconcile(
                BUDGET_DATE,
                100
        );

        assertThat(result.mismatched()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
        assertThat(result.versionConflicts()).isEqualTo(1);
        assertThat(result.mismatchAmount()).isEqualTo(60L);
        verify(budgetStateStore, times(3))
                .repairIfVersionMatches(
                        any(BudgetState.class),
                        eq(3L),
                        eq(7L)
                );
        verify(repository).save(
                eq(CAMPAIGN_ID),
                eq(BUDGET_DATE),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                eq(60L),
                eq("VERSION_CONFLICT"),
                eq(NOW)
        );
    }

    private BudgetReconciliationAdapter adapter(
            int maxRepairAttempts
    ) {
        return new BudgetReconciliationAdapter(
                queryRepository,
                budgetStateStore,
                recoveryService,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                maxRepairAttempts
        );
    }

    private void campaignPage() {
        when(queryRepository.findCampaignIdsAfter(
                anyString(),
                eq(100)
        )).thenReturn(
                List.of(CAMPAIGN_ID),
                List.of()
        );
    }

    private RedisBudgetStateStore.ReadResult readResult(
            long totalVersion,
            long dailyVersion
    ) {
        BudgetState state = new BudgetState(
                CAMPAIGN_ID,
                BUDGET_DATE,
                new Money(1_000L),
                new Money(100L),
                new Money(50L),
                new Money(500L),
                new Money(100L),
                new Money(50L)
        );
        return new RedisBudgetStateStore.ReadResult(
                RedisBudgetStateStore.ReadStatus.FOUND,
                state,
                totalVersion,
                dailyVersion
        );
    }

    private BudgetStateRecoveryQueryRepository.BudgetAggregate
    ledger() {
        return new BudgetStateRecoveryQueryRepository.BudgetAggregate(
                120L,
                40L,
                120L,
                40L
        );
    }
}
