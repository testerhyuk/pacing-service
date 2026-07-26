package com.settlement.pacing.worker.reconciliation.application;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@ConditionalOnProperty(
        prefix = "pacing.worker.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DailyBudgetReconciliationScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(
                    DailyBudgetReconciliationScheduler.class
            );

    private final BudgetReconciliationGateway gateway;
    private final PacingWorkerProperties properties;
    private final PacingWorkerMetrics metrics;
    private final Clock clock;

    public DailyBudgetReconciliationScheduler(
            BudgetReconciliationGateway gateway,
            PacingWorkerProperties properties,
            PacingWorkerMetrics metrics,
            Clock clock
    ) {
        this.gateway = gateway;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${pacing.worker.reconciliation.cron}",
            zone = "${pacing.worker.reconciliation.zone-id}"
    )
    public void reconcilePreviousBudgetDate() {
        PacingWorkerProperties.Reconciliation configuration =
                properties.reconciliation();
        LocalDate budgetDate = clock.instant()
                .atZone(configuration.zoneId())
                .toLocalDate()
                .minusDays(1);

        try {
            BudgetReconciliationResult result = gateway.reconcile(
                    budgetDate,
                    configuration.batchSize()
            );
            metrics.recordBudgetReconciliation(result);
        } catch (RuntimeException exception) {
            metrics.recordBudgetReconciliationFailure(exception);
            log.error(
                    "일 마감 예산 대사에 실패했습니다: {}",
                    budgetDate,
                    exception
            );
        }
    }
}
