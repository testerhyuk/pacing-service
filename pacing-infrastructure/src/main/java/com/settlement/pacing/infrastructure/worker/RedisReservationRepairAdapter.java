package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.budget.ReservationPersistenceService;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.worker.reconciliation.application.ReservationRepairGateway;
import com.settlement.pacing.worker.reconciliation.application.ReservationRepairResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RedisReservationRepairAdapter
        implements ReservationRepairGateway {
    private static final Logger log =
            LoggerFactory.getLogger(
                    RedisReservationRepairAdapter.class
            );

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ReservationPersistenceService persistenceService;
    private final RedisScript<Long> compensateReservationScript;
    private final RedisScript<List> claimReservationRepairsScript;
    private final RedisScript<Long> releaseReservationRepairClaimScript;
    private final RedisScript<Long> completeReservationRepairClaimScript;
    private final Clock clock;
    private final Duration claimTtl;

    public RedisReservationRepairAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ReservationPersistenceService persistenceService,
            RedisScript<Long> compensateReservationScript,
            RedisScript<List> claimReservationRepairsScript,
            RedisScript<Long> releaseReservationRepairClaimScript,
            RedisScript<Long> completeReservationRepairClaimScript,
            Clock clock,
            Duration claimTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.persistenceService = persistenceService;
        this.compensateReservationScript =
                compensateReservationScript;
        this.claimReservationRepairsScript =
                claimReservationRepairsScript;
        this.releaseReservationRepairClaimScript =
                releaseReservationRepairClaimScript;
        this.completeReservationRepairClaimScript =
                completeReservationRepairClaimScript;
        this.clock = clock;
        this.claimTtl = claimTtl;
    }

    @Override
    public ReservationRepairResult repair(
            int batchSize,
            Instant eligibleBefore
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "예약 영속화 복구 batchSize는 0보다 커야 합니다"
            );
        }

        if (eligibleBefore == null) {
            throw new IllegalArgumentException(
                    "예약 영속화 복구 기준 시각은 null일 수 없습니다"
            );
        }

        Instant claimedAt = clock.instant();
        String claimToken = UUID.randomUUID().toString();
        List<String> candidates = claim(
                batchSize,
                eligibleBefore,
                claimedAt,
                claimToken
        );

        int scanned = 0;
        int repaired = 0;
        int alreadyPersisted = 0;
        int removed = 0;
        int failed = 0;

        if (candidates == null || candidates.isEmpty()) {
            return new ReservationRepairResult(
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        for (String member : candidates) {
            scanned++;

            try {
                RepairOutcome outcome = repairOne(member);
                completeClaim(member, claimToken);

                switch (outcome) {
                    case REPAIRED -> repaired++;
                    case ALREADY_PERSISTED -> alreadyPersisted++;
                    case REMOVED -> removed++;
                }
            } catch (RuntimeException exception) {
                releaseClaimSafely(member, claimToken);
                failed++;
                log.error(
                        "예약 영속화 복구에 실패했습니다: member={}",
                        member,
                        exception
                );
            }
        }

        return new ReservationRepairResult(
                scanned,
                repaired,
                alreadyPersisted,
                removed,
                failed
        );
    }

    private RepairOutcome repairOne(String member) {
        RedisKeyFactory.PendingReservationKey pending =
                keyFactory.parseReservationPersistenceMember(member);
        String reservationKey = keyFactory.reservation(
                pending.campaignId(),
                pending.reservationId()
        );
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(reservationKey);

        if (values.isEmpty()) {
            return RepairOutcome.REMOVED;
        }

        BudgetReservation reservation = toReservation(values);
        ReservationPersistenceService.InsertResult insertResult =
                persistenceService.insertIfAbsent(reservation);

        if (!sameIdentity(
                insertResult.reservation(),
                reservation
        )) {
            compensate(reservation);
            return RepairOutcome.REMOVED;
        }

        if (insertResult.inserted()) {
            return RepairOutcome.REPAIRED;
        }

        return RepairOutcome.ALREADY_PERSISTED;
    }

    private BudgetReservation toReservation(
            Map<Object, Object> values
    ) {
        ReservationStatus status = ReservationStatus.valueOf(
                required(values, "status")
        );

        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "PostgreSQL에 없는 Redis 예약이 RESERVED 상태가 아닙니다"
            );
        }

        return new BudgetReservation(
                required(values, "reservationId"),
                required(values, "campaignId"),
                LocalDate.parse(required(values, "budgetDate")),
                new Money(Long.parseLong(
                        required(values, "amount")
                )),
                status,
                Instant.ofEpochMilli(Long.parseLong(
                        required(values, "reservedAtEpochMillis")
                )),
                Instant.ofEpochMilli(Long.parseLong(
                        required(values, "expiresAtEpochMillis")
                ))
        );
    }

    private void compensate(BudgetReservation reservation) {
        Long compensated = redisTemplate.execute(
                compensateReservationScript,
                List.of(
                        keyFactory.totalBudget(
                                reservation.campaignId()
                        ),
                        keyFactory.dailyBudget(
                                reservation.campaignId(),
                                reservation.budgetDate()
                        ),
                        keyFactory.reservation(
                                reservation.campaignId(),
                                reservation.reservationId()
                        ),
                        keyFactory.reservationExpiry(
                                reservation.campaignId()
                        )
                ),
                reservation.reservationId(),
                reservation.campaignId(),
                Long.toString(reservation.amount().amount())
        );

        if (compensated == null || compensated != 1L) {
            throw new IllegalStateException(
                    "충돌한 Redis 예약을 보정할 수 없습니다: "
                            + reservation.reservationId()
            );
        }
    }

    private List<String> claim(
            int batchSize,
            Instant eligibleBefore,
            Instant claimedAt,
            String claimToken
    ) {
        List<?> claimed = redisTemplate.execute(
                claimReservationRepairsScript,
                List.of(
                        keyFactory.reservationPersistencePending(),
                        keyFactory.reservationPersistenceProcessing()
                ),
                Long.toString(eligibleBefore.toEpochMilli()),
                Long.toString(claimedAt.toEpochMilli()),
                Long.toString(
                        claimedAt.plus(claimTtl).toEpochMilli()
                ),
                Integer.toString(batchSize),
                claimToken
        );

        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }

        return claimed.stream()
                .map(Object::toString)
                .toList();
    }

    private void completeClaim(
            String member,
            String claimToken
    ) {
        RedisKeyFactory.PendingReservationKey pending =
                keyFactory.parseReservationPersistenceMember(member);

        redisTemplate.execute(
                completeReservationRepairClaimScript,
                List.of(
                        keyFactory.reservationPersistenceProcessing(),
                        keyFactory.campaignReservationPersistencePending(
                                pending.campaignId()
                        )
                ),
                claimedMember(claimToken, member),
                member
        );
    }

    private void releaseClaimSafely(
            String member,
            String claimToken
    ) {
        try {
            releaseClaim(member, claimToken);
        } catch (RuntimeException releaseException) {
            log.error(
                    "예약 영속화 복구 선점 해제에 실패했습니다: member={}",
                    member,
                    releaseException
            );
        }
    }

    private void releaseClaim(
            String member,
            String claimToken
    ) {
        redisTemplate.execute(
                releaseReservationRepairClaimScript,
                List.of(
                        keyFactory.reservationPersistencePending(),
                        keyFactory.reservationPersistenceProcessing()
                ),
                claimedMember(claimToken, member),
                member,
                Long.toString(clock.instant().toEpochMilli())
        );
    }

    private String claimedMember(
            String claimToken,
            String member
    ) {
        return claimToken + "|" + member;
    }

    private boolean sameIdentity(
            BudgetReservation first,
            BudgetReservation second
    ) {
        return first.campaignId().equals(second.campaignId())
                && first.amount().equals(second.amount());
    }

    private String required(
            Map<Object, Object> values,
            String field
    ) {
        Object value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(
                    "Redis 예약 필드가 없습니다: " + field
            );
        }
        return value.toString();
    }

    private enum RepairOutcome {
        REPAIRED,
        ALREADY_PERSISTED,
        REMOVED
    }
}
