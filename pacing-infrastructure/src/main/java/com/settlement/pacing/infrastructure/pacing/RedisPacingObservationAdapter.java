package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.PacingObservationGateway;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.pacing.DecisionType;
import com.settlement.pacing.core.pacing.PacingObservation;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RedisPacingObservationAdapter
        implements PacingObservationGateway {
    private static final int VALUES_PER_INTERVAL = 4;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<Long> recordDecisionScript;
    private final RedisScript<Long> recordReservationScript;
    private final RedisScript<List> readObservationScript;
    private final Clock clock;
    private final long intervalMillis;
    private final int observationIntervalCount;
    private final Duration retention;
    private final long retentionSeconds;

    public RedisPacingObservationAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<Long> recordDecisionScript,
            RedisScript<Long> recordReservationScript,
            RedisScript<List> readObservationScript,
            PacingProperties properties,
            Clock clock
    ) {
        if (redisTemplate == null
                || keyFactory == null
                || recordDecisionScript == null
                || recordReservationScript == null
                || readObservationScript == null
                || properties == null
                || clock == null) {
            throw new IllegalArgumentException(
                    "페이싱 관측 저장소 구성 값은 null일 수 없습니다"
            );
        }

        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.recordDecisionScript = recordDecisionScript;
        this.recordReservationScript = recordReservationScript;
        this.readObservationScript = readObservationScript;
        this.clock = clock;
        this.intervalMillis =
                properties.rateUpdateInterval().toMillis();
        this.observationIntervalCount = Math.toIntExact(
                properties.observation().window().toMillis()
                        / intervalMillis
        );
        this.retention = properties.observation().window()
                .plus(properties.rateUpdateInterval().multipliedBy(2));
        this.retentionSeconds = Math.max(
                1L,
                (retention.toMillis() + 999L) / 1_000L
        );
    }

    @Override
    public PacingObservation recent(
            String campaignId,
            Instant observedAt
    ) {
        validateCampaignAndTime(campaignId, observedAt);

        long currentBucket = bucketStart(observedAt);
        List<String> keys = new ArrayList<>(
                observationIntervalCount
        );

        for (int offset = 1;
             offset <= observationIntervalCount;
             offset++) {
            keys.add(keyFactory.pacingObservation(
                    campaignId,
                    currentBucket - intervalMillis * offset
            ));
        }

        List<?> values = redisTemplate.execute(
                readObservationScript,
                keys
        );

        if (values == null
                || values.size()
                != observationIntervalCount
                * VALUES_PER_INTERVAL) {
            throw new IllegalStateException(
                    "Redis 페이싱 관측 결과 형식이 올바르지 않습니다"
            );
        }

        List<PacingObservation.Interval> intervals =
                new ArrayList<>(observationIntervalCount);
        boolean hasObservation = false;

        // Redis의 keys는 최신 구간부터 오래된 구간 순서이므로
        // EWMA가 오래된 값 -> 최신 값 순으로 계산할 수 있게 역순으로 저장한다.
        for (int index = values.size() - VALUES_PER_INTERVAL;
             index >= 0;
             index -= VALUES_PER_INTERVAL) {
            long intervalDecisionCount =
                    parseLong(values.get(index));
            long intervalPassCount =
                    parseLong(values.get(index + 1));
            long intervalReservationCount =
                    parseLong(values.get(index + 2));
            long intervalReservedAmount =
                    parseLong(values.get(index + 3));

            if (intervalDecisionCount != 0L
                    || intervalPassCount != 0L
                    || intervalReservationCount != 0L
                    || intervalReservedAmount != 0L) {
                hasObservation = true;
            }

            // 0인 bucket도 버리지 않는다.
            // traffic이 없었던 구간도 EWMA가 과거 traffic을 잊어가게 만드는 데 필요하다.
            intervals.add(
                    new PacingObservation.Interval(
                            intervalDecisionCount,
                            intervalPassCount,
                            intervalReservationCount,
                            new Money(intervalReservedAmount)
                    )
            );
        }

        if (!hasObservation) {
            return PacingObservation.empty();
        }

        return new PacingObservation(intervals);
    }

    @Override
    public boolean recordDecision(
            String requestId,
            String campaignId,
            DecisionType decisionType,
            Instant decidedAt
    ) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "요청 ID는 null이거나 비어있을 수 없습니다"
            );
        }
        if (decisionType == null) {
            throw new IllegalArgumentException(
                    "판단 결과는 null일 수 없습니다"
            );
        }
        validateCampaignAndTime(campaignId, decidedAt);

        long eventBucket = bucketStart(decidedAt);
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.pacingObservation(
                campaignId,
                eventBucket
        ));
        keys.addAll(decisionIdKeys(campaignId, eventBucket));

        Long inserted = redisTemplate.execute(
                recordDecisionScript,
                keys,
                requestId,
                decisionType == DecisionType.PASS ? "1" : "0",
                Long.toString(retentionSeconds)
        );

        return inserted != null && inserted == 1L;
    }

    @Override
    public boolean recordReservation(
            String reservationId,
            String campaignId,
            Money amount,
            Instant reservedAt
    ) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException(
                    "예약 ID는 null이거나 비어있을 수 없습니다"
            );
        }
        if (amount == null || amount.isZero()) {
            throw new IllegalArgumentException(
                    "예약 금액은 0보다 커야 합니다"
            );
        }
        validateCampaignAndTime(campaignId, reservedAt);

        if (reservedAt.isBefore(clock.instant().minus(retention))) {
            return false;
        }

        long eventBucket = bucketStart(reservedAt);
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.pacingObservation(
                campaignId,
                eventBucket
        ));
        keys.addAll(reservationIdKeys(
                campaignId,
                eventBucket
        ));

        Long inserted = redisTemplate.execute(
                recordReservationScript,
                keys,
                reservationId,
                Long.toString(amount.amount()),
                Long.toString(retentionSeconds)
        );

        return inserted != null && inserted == 1L;
    }

    private List<String> decisionIdKeys(
            String campaignId,
            long eventBucket
    ) {
        List<String> keys = new ArrayList<>(
                observationIntervalCount + 2
        );

        for (int offset = 0;
             offset <= observationIntervalCount + 1;
             offset++) {
            keys.add(keyFactory.pacingObservationDecisionIds(
                    campaignId,
                    eventBucket - intervalMillis * offset
            ));
        }

        return keys;
    }

    private List<String> reservationIdKeys(
            String campaignId,
            long eventBucket
    ) {
        List<String> keys = new ArrayList<>(
                observationIntervalCount + 2
        );

        for (int offset = 0;
             offset <= observationIntervalCount + 1;
             offset++) {
            keys.add(keyFactory.pacingObservationReservationIds(
                    campaignId,
                    eventBucket - intervalMillis * offset
            ));
        }

        return keys;
    }

    private long bucketStart(Instant instant) {
        return Math.floorDiv(
                instant.toEpochMilli(),
                intervalMillis
        ) * intervalMillis;
    }

    private long parseLong(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis 페이싱 관측값이 정수가 아닙니다",
                    exception
            );
        }
    }

    private void validateCampaignAndTime(
            String campaignId,
            Instant occurredAt
    ) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "캠페인 ID는 null이거나 비어있을 수 없습니다"
            );
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "발생 시각은 null일 수 없습니다"
            );
        }
    }
}
