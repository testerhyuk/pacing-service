package com.settlement.pacing.api.monitoring;

import java.time.Duration;
import java.time.Instant;

public interface StorageAvailabilityLogSink {
    void failure(
            StorageType storage,
            StorageOperation operation,
            Instant failureStartedAt,
            long failureCount,
            long suppressedFailures,
            RuntimeException exception
    );

    void recovery(
            StorageType storage,
            StorageOperation operation,
            Instant failureStartedAt,
            Instant recoveredAt,
            Duration failureDuration,
            long failureCount
    );
}
