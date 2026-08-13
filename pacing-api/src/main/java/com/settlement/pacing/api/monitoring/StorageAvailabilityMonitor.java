package com.settlement.pacing.api.monitoring;

import com.settlement.pacing.api.config.StorageFailureProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class StorageAvailabilityMonitor {
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration logInterval;
    private final StorageFailureClassifier classifier;
    private final StorageAvailabilityLogSink logSink;
    private final ConcurrentMap<StorageKey, FailureState> states =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<StorageKey, Counter> failureCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<StorageKey, Counter> recoveryCounters =
            new ConcurrentHashMap<>();

    public StorageAvailabilityMonitor(
            MeterRegistry meterRegistry,
            Clock clock,
            StorageFailureProperties properties,
            StorageFailureClassifier classifier,
            StorageAvailabilityLogSink logSink
    ) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.logInterval = properties.logInterval();
        this.classifier = classifier;
        this.logSink = logSink;
    }

    public void recordFailure(
            StorageOperation operation,
            RuntimeException exception
    ) {
        recordFailure(
                classifier.classify(exception),
                operation,
                exception
        );
    }

    public void recordFailure(
            StorageType storage,
            StorageOperation operation,
            RuntimeException exception
    ) {
        StorageKey key = validate(storage, operation);
        failureCounter(key).increment();

        Instant now = clock.instant();
        FailureLog failureLog = states
                .computeIfAbsent(key, ignored -> new FailureState())
                .failure(now, logInterval);

        if (failureLog != null) {
            logSink.failure(
                    storage,
                    operation,
                    failureLog.failureStartedAt(),
                    failureLog.failureCount(),
                    failureLog.suppressedFailures(),
                    exception
            );
        }
    }

    public void recordSuccess(
            StorageType storage,
            StorageOperation operation
    ) {
        StorageKey key = validate(storage, operation);
        FailureState state = states.get(key);

        if (state == null) {
            return;
        }

        Instant recoveredAt = clock.instant();
        RecoveryLog recovery = state.recovery(recoveredAt);

        if (recovery == null) {
            return;
        }

        recoveryCounter(key).increment();
        logSink.recovery(
                storage,
                operation,
                recovery.failureStartedAt(),
                recoveredAt,
                recovery.failureDuration(),
                recovery.failureCount()
        );
    }

    private StorageKey validate(
            StorageType storage,
            StorageOperation operation
    ) {
        if (storage == null || operation == null) {
            throw new IllegalArgumentException(
                    "저장소 장애 메트릭 태그는 null일 수 없습니다"
            );
        }
        return new StorageKey(storage, operation);
    }

    private Counter failureCounter(StorageKey key) {
        return failureCounters.computeIfAbsent(
                key,
                ignored -> Counter.builder(
                                "pacing.api.storage.failure"
                        )
                        .description("저장소 접근 실패 횟수")
                        .tag("storage", key.storage().name())
                        .tag("operation", key.operation().name())
                        .register(meterRegistry)
        );
    }

    private Counter recoveryCounter(StorageKey key) {
        return recoveryCounters.computeIfAbsent(
                key,
                ignored -> Counter.builder(
                                "pacing.api.storage.recovery"
                        )
                        .description("저장소 접근 복구 횟수")
                        .tag("storage", key.storage().name())
                        .tag("operation", key.operation().name())
                        .register(meterRegistry)
        );
    }

    private record StorageKey(
            StorageType storage,
            StorageOperation operation
    ) {
    }

    private record FailureLog(
            Instant failureStartedAt,
            long failureCount,
            long suppressedFailures
    ) {
    }

    private record RecoveryLog(
            Instant failureStartedAt,
            Duration failureDuration,
            long failureCount
    ) {
    }

    private static final class FailureState {
        private boolean failing;
        private Instant failureStartedAt;
        private Instant lastLoggedAt;
        private long failureCount;
        private long suppressedFailures;

        private synchronized FailureLog failure(
                Instant now,
                Duration logInterval
        ) {
            if (!failing) {
                failing = true;
                failureStartedAt = now;
                lastLoggedAt = now;
                failureCount = 1L;
                suppressedFailures = 0L;
                return new FailureLog(now, 1L, 0L);
            }

            failureCount++;

            if (Duration.between(lastLoggedAt, now)
                    .compareTo(logInterval) < 0) {
                suppressedFailures++;
                return null;
            }

            FailureLog result = new FailureLog(
                    failureStartedAt,
                    failureCount,
                    suppressedFailures
            );
            lastLoggedAt = now;
            suppressedFailures = 0L;
            return result;
        }

        private synchronized RecoveryLog recovery(
                Instant recoveredAt
        ) {
            if (!failing) {
                return null;
            }

            RecoveryLog result = new RecoveryLog(
                    failureStartedAt,
                    Duration.between(
                            failureStartedAt,
                            recoveredAt
                    ),
                    failureCount
            );

            failing = false;
            failureStartedAt = null;
            lastLoggedAt = null;
            failureCount = 0L;
            suppressedFailures = 0L;
            return result;
        }
    }
}
