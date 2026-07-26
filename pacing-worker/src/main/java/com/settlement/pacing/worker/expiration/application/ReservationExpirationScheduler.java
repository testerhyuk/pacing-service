package com.settlement.pacing.worker.expiration.application;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.monitoring.PacingWorkerMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "pacing.worker.expiration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationExpirationScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(
                    ReservationExpirationScheduler.class
            );

    private final ExpiredReservationGateway gateway;
    private final PacingWorkerProperties properties;
    private final PacingWorkerMetrics metrics;
    private final Clock clock;

    public ReservationExpirationScheduler(
            ExpiredReservationGateway gateway,
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
                    "${pacing.worker.expiration.fixed-delay}"
    )
    public void expireReservations() {
        Instant startedAt = clock.instant();

        try {
            ExpirationBatchResult result = gateway.expire(
                    startedAt,
                    properties.expiration().batchSize()
            );
            metrics.recordExpiration(
                    result,
                    Duration.between(
                            startedAt,
                            clock.instant()
                    )
            );
        } catch (RuntimeException exception) {
            metrics.recordExpirationFailure(exception);
            log.error(
                    "예약 만료 배치 처리에 실패했습니다",
                    exception
            );
        }
    }
}
