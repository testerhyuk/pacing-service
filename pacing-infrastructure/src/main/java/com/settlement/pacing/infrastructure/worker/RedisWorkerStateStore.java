package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingResult;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.error.NonRetryableBillingEventException;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RedisWorkerStateStore {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<List> initializeReservationScript;
    private final RedisScript<List> applyBillingEventScript;
    private final RedisScript<List> expireReservationScript;
    private final PacingWorkerProperties properties;

    public RedisWorkerStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<List> initializeReservationScript,
            RedisScript<List> applyBillingEventScript,
            RedisScript<List> expireReservationScript,
            PacingWorkerProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.initializeReservationScript =
                initializeReservationScript;
        this.applyBillingEventScript = applyBillingEventScript;
        this.expireReservationScript = expireReservationScript;
        this.properties = properties;
    }

    public RedisReservationSnapshot getOrInitialize(
            BudgetReservationEntity entity
    ) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "예약 Entity는 null일 수 없습니다"
            );
        }

        redisTemplate.execute(
                initializeReservationScript,
                List.of(
                        keyFactory.reservation(
                                entity.getCampaignId(),
                                entity.getReservationId()
                        ),
                        keyFactory.reservationExpiry(
                                entity.getCampaignId()
                        )
                ),
                entity.getReservationId(),
                entity.getCampaignId(),
                entity.getBudgetDate().toString(),
                Long.toString(entity.getAmount()),
                Long.toString(entity.getAppliedAmount()),
                entity.getStatus().name(),
                Long.toString(
                        entity.getReservedAt().toEpochMilli()
                ),
                Long.toString(
                        entity.getExpiresAt().toEpochMilli()
                ),
                Long.toString(entity.getVersion()),
                Long.toString(
                        properties.terminalReservationTtl()
                                .toMillis()
                )
        );

        return read(
                entity.getCampaignId(),
                entity.getReservationId()
        );
    }

    public RedisReservationSnapshot read(
            String campaignId,
            String reservationId
    ) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(keyFactory.reservation(
                        campaignId,
                        reservationId
                ));

        if (values.isEmpty()) {
            throw new RetryableBillingEventException(
                    "Redis 예약 상태를 조회할 수 없습니다: "
                            + reservationId
            );
        }

        try {
            String storedReservationId = required(
                    values,
                    "reservationId"
            );
            String storedCampaignId = required(
                    values,
                    "campaignId"
            );

            if (!reservationId.equals(storedReservationId)
                    || !campaignId.equals(storedCampaignId)) {
                throw new NonRetryableBillingEventException(
                        "Redis 예약 식별자가 요청과 일치하지 않습니다: "
                                + reservationId
                );
            }

            BudgetReservation reservation =
                    new BudgetReservation(
                            storedReservationId,
                            storedCampaignId,
                            LocalDate.parse(required(
                                    values,
                                    "budgetDate"
                            )),
                            new Money(parseNonNegativeLong(
                                    values,
                                    "amount"
                            )),
                            ReservationStatus.valueOf(required(
                                    values,
                                    "status"
                            )),
                            Instant.ofEpochMilli(
                                    parseNonNegativeLong(
                                            values,
                                            "reservedAtEpochMillis"
                                    )
                            ),
                            Instant.ofEpochMilli(
                                    parseNonNegativeLong(
                                            values,
                                            "expiresAtEpochMillis"
                                    )
                            )
                    );

            return new RedisReservationSnapshot(
                    reservation,
                    new Money(parseNonNegativeLong(
                            values,
                            "appliedAmount"
                    )),
                    parseNonNegativeLong(values, "version")
            );
        } catch (NonRetryableBillingEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NonRetryableBillingEventException(
                    "Redis 예약 상태 값이 올바르지 않습니다: "
                            + reservationId,
                    exception
            );
        }
    }

    /**
     * Redis에는 반영됐지만 PostgreSQL 완료 기록이 남지 않은 이벤트를 찾는다.
     */
    public Optional<RedisBillingTransition> findAppliedBillingEvent(
            String campaignId,
            String eventId,
            String reservationId
    ) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(keyFactory.billingEvent(
                        campaignId,
                        eventId
                ));

        if (values.isEmpty()) {
            return Optional.empty();
        }

        try {
            String storedEventId = required(values, "eventId");
            String storedReservationId = required(
                    values,
                    "reservationId"
            );

            if (!eventId.equals(storedEventId)
                    || !reservationId.equals(
                    storedReservationId
            )) {
                throw new NonRetryableBillingEventException(
                        "Redis 과금 이벤트 식별자가 요청과 일치하지 않습니다: "
                                + eventId
                );
            }

            return Optional.of(new RedisBillingTransition(
                    RedisBillingTransition.RedisTransitionStatus
                            .ALREADY_APPLIED,
                    storedEventId,
                    storedReservationId,
                    ReservationStatus.valueOf(required(
                            values,
                            "reservationStatus"
                    )),
                    new Money(parseNonNegativeLong(
                            values,
                            "appliedAmount"
                    )),
                    parseNonNegativeLong(
                            values,
                            "reservationVersion"
                    ),
                    new Money(parseNonNegativeLong(
                            values,
                            "totalOverageAmount"
                    )),
                    new Money(parseNonNegativeLong(
                            values,
                            "dailyOverageAmount"
                    ))
            ));
        } catch (NonRetryableBillingEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NonRetryableBillingEventException(
                    "Redis 과금 이벤트 처리 결과가 올바르지 않습니다: "
                            + eventId,
                    exception
            );
        }
    }

    public RedisBillingTransition applyBillingEvent(
            BillingEvent event,
            BudgetState currentBudget,
            RedisReservationSnapshot currentReservation,
            BillingResult result
    ) {
        /*
         * 공유 BudgetState의 절대값을 Redis에 덮어쓰지 않는다.
         *
         * 서로 다른 reservation의 Billing Event가 동시에 처리되더라도
         * 각 이벤트가 변경해야 하는 금액(delta)만 계산한 뒤
         * Lua 내부에서 현재 Redis 값에 원자적으로 반영한다.
         */
        long totalSpentDelta = delta(
                currentBudget.totalSpentAmount(),
                result.budgetState().totalSpentAmount()
        );

        long totalReservedDelta = delta(
                currentBudget.totalReservedAmount(),
                result.budgetState().totalReservedAmount()
        );

        long dailySpentDelta = delta(
                currentBudget.dailySpentAmount(),
                result.budgetState().dailySpentAmount()
        );

        long dailyReservedDelta = delta(
                currentBudget.dailyReservedAmount(),
                result.budgetState().dailyReservedAmount()
        );

        List<?> raw = redisTemplate.execute(
                applyBillingEventScript,
                List.of(
                        keyFactory.totalBudget(
                                currentBudget.campaignId()
                        ),
                        keyFactory.dailyBudget(
                                currentBudget.campaignId(),
                                currentBudget.budgetDate()
                        ),
                        keyFactory.reservation(
                                currentBudget.campaignId(),
                                currentReservation.reservation()
                                        .reservationId()
                        ),
                        keyFactory.reservationExpiry(
                                currentBudget.campaignId()
                        ),
                        keyFactory.billingEvent(
                                currentBudget.campaignId(),
                                event.eventId()
                        )
                ),

                // 1
                event.eventId(),

                // 2
                currentReservation.reservation().reservationId(),

                // 3
                currentBudget.campaignId(),

                // 4
                currentBudget.budgetDate().toString(),

                // 5
                amount(currentReservation.reservation().amount()),

                // 6
                amount(currentReservation.appliedAmount()),

                // 7
                currentReservation.reservation().status().name(),

                // 8
                epochMillis(
                        currentReservation.reservation().reservedAt()
                ),

                // 9
                epochMillis(
                        currentReservation.reservation().expiresAt()
                ),

                // 10
                Long.toString(currentReservation.version()),

                // 11
                Long.toString(totalSpentDelta),

                // 12
                Long.toString(totalReservedDelta),

                // 13
                Long.toString(dailySpentDelta),

                // 14
                Long.toString(dailyReservedDelta),

                // 15
                result.reservation().status().name(),

                // 16
                amount(result.appliedAmount()),

                // 17
                Long.toString(
                        properties.processedEventTtl().toMillis()
                ),

                // 18
                Long.toString(
                        properties.terminalReservationTtl()
                                .toMillis()
                )
        );

        return parseBillingTransition(raw);
    }

    private long delta(
            Money before,
            Money after
    ) {
        return Math.subtractExact(
                after.amount(),
                before.amount()
        );
    }

    public RedisExpirationTransition expire(
            Instant now,
            BudgetState currentBudget,
            RedisReservationSnapshot currentReservation,
            BudgetState nextBudget
    ) {
        List<?> raw = redisTemplate.execute(
                expireReservationScript,
                List.of(
                        keyFactory.totalBudget(
                                currentBudget.campaignId()
                        ),
                        keyFactory.dailyBudget(
                                currentBudget.campaignId(),
                                currentBudget.budgetDate()
                        ),
                        keyFactory.reservation(
                                currentBudget.campaignId(),
                                currentReservation.reservation()
                                        .reservationId()
                        ),
                        keyFactory.reservationExpiry(
                                currentBudget.campaignId()
                        )
                ),
                epochMillis(now),
                currentReservation.reservation().reservationId(),
                currentBudget.campaignId(),
                currentBudget.budgetDate().toString(),
                amount(currentReservation.reservation().amount()),
                amount(currentBudget.totalBudget()),
                amount(currentBudget.totalSpentAmount()),
                amount(currentBudget.totalReservedAmount()),
                amount(currentBudget.dailyBudgetLimit()),
                amount(currentBudget.dailySpentAmount()),
                amount(currentBudget.dailyReservedAmount()),
                amount(currentReservation.appliedAmount()),
                epochMillis(
                        currentReservation.reservation().reservedAt()
                ),
                epochMillis(
                        currentReservation.reservation().expiresAt()
                ),
                Long.toString(currentReservation.version()),
                amount(nextBudget.totalReservedAmount()),
                amount(nextBudget.dailyReservedAmount()),
                Long.toString(
                        properties.terminalReservationTtl()
                                .toMillis()
                )
        );

        if (raw == null || raw.isEmpty()) {
            throw new RetryableBillingEventException(
                    "Redis 예약 만료 결과가 비어있습니다"
            );
        }

        String status = value(raw, 0);
        return switch (status) {
            case "EXPIRED" -> {
                if (raw.size() != 3) {
                    throw new NonRetryableBillingEventException(
                            "Redis 예약 만료 결과 필드 수가 올바르지 않습니다"
                    );
                }

                yield new RedisExpirationTransition(
                        RedisExpirationTransition.Status.EXPIRED,
                        value(raw, 1),
                        parseNonNegativeLong(value(raw, 2))
                );
            }
            case "SKIPPED", "NOT_DUE" ->
                    new RedisExpirationTransition(
                            RedisExpirationTransition.Status.SKIPPED,
                            currentReservation.reservation()
                                    .reservationId(),
                            currentReservation.version()
                    );
            case "STATE_CONFLICT", "STATE_MISSING" ->
                    new RedisExpirationTransition(
                            RedisExpirationTransition.Status.CONFLICT,
                            currentReservation.reservation()
                                    .reservationId(),
                            currentReservation.version()
                    );
            default -> throw new NonRetryableBillingEventException(
                    "알 수 없는 Redis 예약 만료 결과입니다: "
                            + status
            );
        };
    }

    private RedisBillingTransition parseBillingTransition(
            List<?> raw
    ) {
        if (raw == null || raw.isEmpty()) {
            throw new RetryableBillingEventException(
                    "Redis 과금 이벤트 처리 결과가 비어있습니다"
            );
        }

        String status = value(raw, 0);

        if ("STATE_CONFLICT".equals(status)) {
            throw new RetryableBillingEventException(
                    "Redis 예약 상태가 처리 중 변경됐습니다: "
                            + status
            );
        }

        if ("STATE_MISSING".equals(status)) {
            throw new RetryableBillingEventException(
                    "Redis 과금 상태를 찾을 수 없습니다: "
                            + status
            );
        }

        if ("INVALID_STATE".equals(status)) {
            throw new NonRetryableBillingEventException(
                    "Redis 예산 상태가 올바르지 않아 과금을 적용할 수 없습니다"
            );
        }

        if ("CORRUPTED".equals(status)) {
            throw new NonRetryableBillingEventException(
                    "Redis 과금 이벤트 처리 이력이 손상됐습니다"
            );
        }

        if (!"APPLIED".equals(status)
                && !"ALREADY_APPLIED".equals(status)) {
            throw new NonRetryableBillingEventException(
                    "알 수 없는 Redis 과금 이벤트 처리 결과입니다: "
                            + status
            );
        }

        if (raw.size() != 8) {
            throw new NonRetryableBillingEventException(
                    "Redis 과금 이벤트 처리 결과 필드 수가 올바르지 않습니다"
            );
        }

        try {
            return new RedisBillingTransition(
                    RedisBillingTransition.RedisTransitionStatus
                            .valueOf(status),
                    value(raw, 1),
                    value(raw, 2),
                    ReservationStatus.valueOf(value(raw, 3)),
                    new Money(parseNonNegativeLong(value(raw, 4))),
                    parseNonNegativeLong(value(raw, 5)),
                    new Money(parseNonNegativeLong(value(raw, 6))),
                    new Money(parseNonNegativeLong(value(raw, 7)))
            );
        } catch (IllegalArgumentException exception) {
            throw new NonRetryableBillingEventException(
                    "Redis 과금 이벤트 처리 결과 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    private Money overage(Money effective, Money limit) {
        return limit.isLessThan(effective)
                ? effective.subtract(limit)
                : Money.zero();
    }

    private String required(
            Map<Object, Object> values,
            String field
    ) {
        Object value = values.get(field);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Redis 예약 필드가 없습니다: " + field
            );
        }

        return value.toString();
    }

    private long parseNonNegativeLong(
            Map<Object, Object> values,
            String field
    ) {
        return parseNonNegativeLong(required(values, field));
    }

    private long parseNonNegativeLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "Redis 숫자 값은 음수일 수 없습니다"
            );
        }
        return parsed;
    }

    private String value(List<?> values, int index) {
        Object value = values.get(index);
        if (value == null) {
            throw new NonRetryableBillingEventException(
                    "Redis 처리 결과에 null이 포함됐습니다"
            );
        }
        return value.toString();
    }

    private String amount(Money money) {
        return Long.toString(money.amount());
    }

    private String epochMillis(Instant instant) {
        return Long.toString(instant.toEpochMilli());
    }
}
