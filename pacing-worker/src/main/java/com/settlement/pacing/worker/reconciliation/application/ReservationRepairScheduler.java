package com.settlement.pacing.worker.reconciliation.application;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "pacing.worker.reservation-repair",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationRepairScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(
                    ReservationRepairScheduler.class
            );

    private final ReservationRepairGateway gateway;
    private final PacingWorkerProperties properties;
    private final PacingWorkerMetrics metrics;

    public ReservationRepairScheduler(
            ReservationRepairGateway gateway,
            PacingWorkerProperties properties,
            PacingWorkerMetrics metrics
    ) {
        this.gateway = gateway;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString =
                    "${pacing.worker.reservation-repair.fixed-delay}"
    )
    public void repairReservations() {
        try {
            ReservationRepairResult result = gateway.repair(
                    properties.reservationRepair().batchSize()
            );
            metrics.recordReservationRepair(result);
        } catch (RuntimeException exception) {
            metrics.recordReservationRepairFailure(exception);
            log.error(
                    "Redis 예약 영속화 복구 배치에 실패했습니다",
                    exception
            );
        }
    }
}
