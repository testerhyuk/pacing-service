package com.settlement.pacing.worker.monitoring;

import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingResult;
import com.settlement.pacing.worker.expiration.application.ExpirationBatchResult;
import com.settlement.pacing.worker.reconciliation.application.ReservationRepairResult;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PacingWorkerMetrics {
    private final MeterRegistry meterRegistry;

    public PacingWorkerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordBilling(
            Timer.Sample sample,
            BillingEventType eventType,
            BillingEventProcessingResult result
    ) {
        sample.stop(Timer.builder("pacing.worker.billing")
                .tag("eventType", eventType.name())
                .tag("status", result.status().name())
                .tag(
                        "overBudget",
                        Boolean.toString(result.overBudget())
                )
                .register(meterRegistry));

        if (result.applied()) {
            recordOverage(
                    "total",
                    result.totalOverageAmount().amount()
            );
            recordOverage(
                    "daily",
                    result.dailyOverageAmount().amount()
            );
        }
    }

    public void recordBillingFailure(
            Timer.Sample sample,
            RuntimeException exception
    ) {
        sample.stop(Timer.builder("pacing.worker.billing")
                .tag("eventType", "UNKNOWN")
                .tag("status", "FAILURE")
                .tag(
                        "reason",
                        exception.getClass().getSimpleName()
                )
                .register(meterRegistry));
    }

    public void recordDeadLetter(String eventType) {
        Counter.builder("pacing.worker.billing.dlt")
                .tag(
                        "eventType",
                        eventType == null ? "UNKNOWN" : eventType
                )
                .register(meterRegistry)
                .increment();
    }

    public void recordExpiration(
            ExpirationBatchResult result,
            Duration duration
    ) {
        Timer.builder("pacing.worker.expiration")
                .register(meterRegistry)
                .record(duration);

        Counter.builder("pacing.worker.expiration.result")
                .tag("outcome", "EXPIRED")
                .register(meterRegistry)
                .increment(result.expired());

        Counter.builder("pacing.worker.expiration.result")
                .tag("outcome", "CONFLICT")
                .register(meterRegistry)
                .increment(result.conflicts());
    }

    public void recordExpirationFailure(RuntimeException exception) {
        Counter.builder("pacing.worker.expiration.failure")
                .tag(
                        "reason",
                        exception.getClass().getSimpleName()
                )
                .register(meterRegistry)
                .increment();
    }

    public void recordReservationRepair(
            ReservationRepairResult result
    ) {
        recordRepairResult("REPAIRED", result.repaired());
        recordRepairResult("REMOVED", result.removed());
        recordRepairResult("FAILED", result.failed());
    }

    public void recordReservationRepairFailure(
            RuntimeException exception
    ) {
        Counter.builder(
                        "pacing.worker.reservation.repair.failure"
                )
                .tag(
                        "reason",
                        exception.getClass().getSimpleName()
                )
                .register(meterRegistry)
                .increment();
    }

    public void recordBudgetReconciliation(
            BudgetReconciliationResult result
    ) {
        recordReconciliationResult("MATCHED", result.matched());
        recordReconciliationResult(
                "MISMATCHED",
                result.mismatched()
        );
        recordReconciliationResult(
                "UNAVAILABLE",
                result.unavailable()
        );

        if (result.mismatchAmount() > 0L) {
            Counter.builder(
                            "pacing.worker.reconciliation.mismatch.amount"
                    )
                    .register(meterRegistry)
                    .increment(result.mismatchAmount());
        }
    }

    public void recordBudgetReconciliationFailure(
            RuntimeException exception
    ) {
        Counter.builder(
                        "pacing.worker.reconciliation.failure"
                )
                .tag(
                        "reason",
                        exception.getClass().getSimpleName()
                )
                .register(meterRegistry)
                .increment();
    }

    private void recordRepairResult(
            String outcome,
            int count
    ) {
        if (count <= 0) {
            return;
        }

        Counter.builder("pacing.worker.reservation.repair")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment(count);
    }

    private void recordReconciliationResult(
            String outcome,
            int count
    ) {
        if (count <= 0) {
            return;
        }

        Counter.builder("pacing.worker.reconciliation")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment(count);
    }

    private void recordOverage(String scope, long amount) {
        if (amount <= 0) {
            return;
        }

        Counter.builder("pacing.worker.billing.overage")
                .tag("scope", scope)
                .register(meterRegistry)
                .increment(amount);
    }
}
