package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.Rate;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RedisPacingStateAdapter
        implements PacingStateGateway {
    private static final long INITIAL_VERSION = 0L;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<List> getOrInitializeScript;
    private final RedisScript<List> compareAndSetScript;
    private final PacingStateSnapshotStore snapshotStore;
    private final PacingInfrastructureMetrics metrics;

    public RedisPacingStateAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<List> getOrInitializeScript,
            RedisScript<List> compareAndSetScript,
            PacingStateSnapshotStore snapshotStore,
            PacingInfrastructureMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.getOrInitializeScript = getOrInitializeScript;
        this.compareAndSetScript = compareAndSetScript;
        this.snapshotStore = snapshotStore;
        this.metrics = metrics;
    }

    @Override
    public PacingStateSnapshot getOrInitialize(
            String campaignId,
            PacingState initialState
    ) {
        validateCampaignId(campaignId);

        if (initialState == null) {
            throw new IllegalArgumentException(
                    "초기 페이싱 상태는 null일 수 없습니다"
            );
        }

        Optional<PacingStateSnapshot> current =
                readFromRedis(campaignId);

        if (current.isPresent()) {
            return current.get();
        }

        PacingStateSnapshot fallback = snapshotStore.find(campaignId)
                .orElseGet(() -> new PacingStateSnapshot(
                        initialState,
                        INITIAL_VERSION
                ));

        PacingStateSnapshot actual = executeGetOrInitialize(
                campaignId,
                fallback
        );
        snapshotStore.saveIfNewer(campaignId, actual);
        return actual;
    }

    @Override
    public Optional<PacingStateSnapshot> findByCampaignId(
            String campaignId
    ) {
        validateCampaignId(campaignId);

        Optional<PacingStateSnapshot> redisSnapshot =
                readFromRedis(campaignId);

        if (redisSnapshot.isPresent()) {
            return redisSnapshot;
        }

        return snapshotStore.find(campaignId)
                .map(snapshot -> executeGetOrInitialize(
                        campaignId,
                        snapshot
                ));
    }

    @Override
    public boolean compareAndSet(
            String campaignId,
            long expectedVersion,
            PacingState newState
    ) {
        validateCampaignId(campaignId);

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "예상 version은 0 이상이어야 합니다"
            );
        }

        if (newState == null) {
            throw new IllegalArgumentException(
                    "새 페이싱 상태는 null일 수 없습니다"
            );
        }

        CompareAndSetResult result = executeCompareAndSet(
                campaignId,
                expectedVersion,
                newState
        );

        if (result.status() == CompareAndSetStatus.KEY_MISSING) {
            Optional<PacingStateSnapshot> persisted =
                    snapshotStore.find(campaignId);

            if (persisted.isEmpty()) {
                metrics.recordPacingStateCas("KEY_MISSING");
                return false;
            }

            executeGetOrInitialize(campaignId, persisted.get());
            result = executeCompareAndSet(
                    campaignId,
                    expectedVersion,
                    newState
            );
        }

        if (result.status() == CompareAndSetStatus.VERSION_MISMATCH) {
            metrics.recordPacingStateCas("VERSION_MISMATCH");
            return false;
        }

        if (result.status() != CompareAndSetStatus.UPDATED) {
            throw corrupted(
                    "Redis 페이싱 상태를 갱신할 수 없습니다: "
                            + result.status(),
                    null
            );
        }

        PacingStateSnapshot updated = new PacingStateSnapshot(
                newState,
                result.version()
        );
        snapshotStore.saveIfNewer(campaignId, updated);
        metrics.recordPacingStateCas("UPDATED");
        return true;
    }

    private PacingStateSnapshot executeGetOrInitialize(
            String campaignId,
            PacingStateSnapshot fallback
    ) {
        List<?> result = redisTemplate.execute(
                getOrInitializeScript,
                List.of(keyFactory.pacingState(campaignId)),
                Double.toString(
                        fallback.pacingState().pacingRate().value()
                ),
                Long.toString(
                        fallback.pacingState()
                                .updatedAt()
                                .toEpochMilli()
                ),
                Long.toString(fallback.version())
        );

        return parseSnapshotResult(result);
    }

    private CompareAndSetResult executeCompareAndSet(
            String campaignId,
            long expectedVersion,
            PacingState newState
    ) {
        List<?> result = redisTemplate.execute(
                compareAndSetScript,
                List.of(keyFactory.pacingState(campaignId)),
                Long.toString(expectedVersion),
                Double.toString(newState.pacingRate().value()),
                Long.toString(newState.updatedAt().toEpochMilli())
        );

        if (result == null || result.isEmpty()) {
            throw corrupted(
                    "Redis 페이싱 상태 갱신 결과가 비어있습니다",
                    null
            );
        }

        CompareAndSetStatus status;
        try {
            status = CompareAndSetStatus.valueOf(value(result, 0));
        } catch (IllegalArgumentException exception) {
            throw corrupted(
                    "알 수 없는 Redis 페이싱 상태 갱신 결과입니다",
                    exception
            );
        }

        if (status == CompareAndSetStatus.CORRUPTED) {
            throw corrupted(
                    "Redis 페이싱 상태 Hash의 필수 필드가 누락됐습니다",
                    null
            );
        }

        long version = -1L;
        if (status == CompareAndSetStatus.UPDATED
                || status == CompareAndSetStatus.VERSION_MISMATCH) {
            if (result.size() < 2) {
                throw corrupted(
                        "Redis 페이싱 상태 version이 누락됐습니다",
                        null
                );
            }
            version = parseNonNegativeLong(result, 1);
        }

        return new CompareAndSetResult(status, version);
    }

    private Optional<PacingStateSnapshot> readFromRedis(
            String campaignId
    ) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(keyFactory.pacingState(campaignId));

        if (values.isEmpty()) {
            return Optional.empty();
        }

        Object pacingRate = values.get("pacingRate");
        Object updatedAt = values.get("updatedAtEpochMillis");
        Object version = values.get("version");

        if (pacingRate == null || updatedAt == null || version == null) {
            throw corrupted(
                    "Redis 페이싱 상태 Hash의 필수 필드가 누락됐습니다",
                    null
            );
        }

        try {
            return Optional.of(new PacingStateSnapshot(
                    new PacingState(
                            new Rate(Double.parseDouble(
                                    pacingRate.toString()
                            )),
                            Instant.ofEpochMilli(Long.parseLong(
                                    updatedAt.toString()
                            ))
                    ),
                    parseNonNegativeLong(version.toString())
            ));
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 페이싱 상태 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    private PacingStateSnapshot parseSnapshotResult(
            List<?> result
    ) {
        if (result == null || result.isEmpty()) {
            throw corrupted(
                    "Redis 페이싱 상태 조회 결과가 비어있습니다",
                    null
            );
        }

        String status = value(result, 0);
        if ("CORRUPTED".equals(status)) {
            throw corrupted(
                    "Redis 페이싱 상태 Hash의 필수 필드가 누락됐습니다",
                    null
            );
        }

        if (!"OK".equals(status) || result.size() != 4) {
            throw corrupted(
                    "알 수 없는 Redis 페이싱 상태 조회 결과입니다",
                    null
            );
        }

        try {
            return new PacingStateSnapshot(
                    new PacingState(
                            new Rate(Double.parseDouble(
                                    value(result, 1)
                            )),
                            Instant.ofEpochMilli(
                                    Long.parseLong(value(result, 2))
                            )
                    ),
                    parseNonNegativeLong(result, 3)
            );
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 페이싱 상태 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    private long parseNonNegativeLong(
            List<?> result,
            int index
    ) {
        return parseNonNegativeLong(value(result, index));
    }

    private long parseNonNegativeLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "version은 0 이상이어야 합니다"
            );
        }
        return parsed;
    }

    private String value(List<?> result, int index) {
        Object value = result.get(index);
        if (value == null) {
            throw corrupted(
                    "Redis 페이싱 상태 결과에 null이 포함됐습니다",
                    null
            );
        }
        return value.toString();
    }

    private void validateCampaignId(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }
    }

    private DataIntegrityViolationException corrupted(
            String message,
            Throwable cause
    ) {
        return cause == null
                ? new DataIntegrityViolationException(message)
                : new DataIntegrityViolationException(message, cause);
    }

    private enum CompareAndSetStatus {
        UPDATED,
        VERSION_MISMATCH,
        KEY_MISSING,
        CORRUPTED
    }

    private record CompareAndSetResult(
            CompareAndSetStatus status,
            long version
    ) {
    }
}
