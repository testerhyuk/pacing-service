package com.settlement.pacing.api.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class Slf4jStorageAvailabilityLogSink
        implements StorageAvailabilityLogSink {
    private static final Logger log = LoggerFactory.getLogger(
            Slf4jStorageAvailabilityLogSink.class
    );

    @Override
    public void failure(
            StorageType storage,
            StorageOperation operation,
            Instant failureStartedAt,
            long failureCount,
            long suppressedFailures,
            RuntimeException exception
    ) {
        log.error(
                "저장소 접근 장애: storage={}, operation={}, "
                        + "failureStartedAt={}, failureCount={}, "
                        + "suppressedFailures={}",
                storage,
                operation,
                failureStartedAt,
                failureCount,
                suppressedFailures,
                exception
        );
    }

    @Override
    public void recovery(
            StorageType storage,
            StorageOperation operation,
            Instant failureStartedAt,
            Instant recoveredAt,
            Duration failureDuration,
            long failureCount
    ) {
        log.info(
                "저장소 접근 복구: storage={}, operation={}, "
                        + "failureStartedAt={}, recoveredAt={}, "
                        + "failureDurationMillis={}, failureCount={}",
                storage,
                operation,
                failureStartedAt,
                recoveredAt,
                failureDuration.toMillis(),
                failureCount
        );
    }
}
