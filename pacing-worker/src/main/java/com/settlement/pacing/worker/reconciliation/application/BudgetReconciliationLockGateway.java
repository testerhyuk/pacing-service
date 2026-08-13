package com.settlement.pacing.worker.reconciliation.application;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

public interface BudgetReconciliationLockGateway {
    Optional<LockHandle> tryAcquire(
            LocalDate budgetDate,
            Duration ttl
    );

    void release(LockHandle lockHandle);

    record LockHandle(
            LocalDate budgetDate,
            String token
    ) {
        public LockHandle {
            if (budgetDate == null
                    || token == null
                    || token.isBlank()) {
                throw new IllegalArgumentException(
                        "예산 대사 Lock 정보는 비어있을 수 없습니다"
                );
            }
        }
    }
}
