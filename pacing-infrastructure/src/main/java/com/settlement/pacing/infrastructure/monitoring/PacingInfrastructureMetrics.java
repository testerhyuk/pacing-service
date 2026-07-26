package com.settlement.pacing.infrastructure.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class PacingInfrastructureMetrics {
    private final MeterRegistry meterRegistry;

    public PacingInfrastructureMetrics(
            MeterRegistry meterRegistry
    ) {
        this.meterRegistry = meterRegistry;
    }

    public void recordBudgetRecovery(String outcome) {
        counter(
                "pacing.infrastructure.budget.recovery",
                "outcome",
                outcome
        ).increment();
    }

    public void recordReservation(String status) {
        counter(
                "pacing.infrastructure.reservation",
                "status",
                status
        ).increment();
    }

    public void recordPacingStateCas(String outcome) {
        counter(
                "pacing.infrastructure.pacing_state.cas",
                "outcome",
                outcome
        ).increment();
    }

    public void recordNonce(boolean stored) {
        counter(
                "pacing.infrastructure.nonce",
                "result",
                stored ? "STORED" : "DUPLICATE"
        ).increment();
    }

    public void recordRateLimit(boolean allowed) {
        counter(
                "pacing.infrastructure.rate_limit",
                "result",
                allowed ? "ALLOWED" : "REJECTED"
        ).increment();
    }

    private Counter counter(
            String name,
            String tagName,
            String tagValue
    ) {
        return Counter.builder(name)
                .tag(tagName, tagValue)
                .register(meterRegistry);
    }
}
