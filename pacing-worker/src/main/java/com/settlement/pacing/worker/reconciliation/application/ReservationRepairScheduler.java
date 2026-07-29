package com.settlement.pacing.worker.reconciliation.application;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

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
    private final Clock clock;

    public ReservationRepairScheduler(
            ReservationRepairGateway gateway,
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
            fixedDelayString =
                    "${pacing.worker.reservation-repair.fixed-delay}"
    )
    public void repairReservations() {
        try {
            Instant eligibleBefore = clock.instant()
                    .minus(
                            properties.reservationRepair()
                                    .gracePeriod()
                    );

            ReservationRepairResult result = gateway.repair(
                    properties.reservationRepair().batchSize(),
                    eligibleBefore
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
