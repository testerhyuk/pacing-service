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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    public RedisReservationRepairAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ReservationPersistenceService persistenceService,
            RedisScript<Long> compensateReservationScript
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.persistenceService = persistenceService;
        this.compensateReservationScript =
                compensateReservationScript;
    }

    @Override
    public ReservationRepairResult repair(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "예약 복구 batchSize는 0보다 커야 합니다"
            );
        }

        int scanned = 0;
        int repaired = 0;
        int removed = 0;
        int failed = 0;

        ScanOptions options = ScanOptions.scanOptions()
                .count(batchSize)
                .build();

        try (Cursor<String> cursor = redisTemplate.opsForSet()
                .scan(
                        keyFactory.reservationPersistencePending(),
                        options
                )) {
            while (cursor.hasNext() && scanned < batchSize) {
                String member = cursor.next();
                scanned++;

                try {
                    RepairOutcome outcome = repairOne(member);
                    if (outcome == RepairOutcome.REPAIRED) {
                        repaired++;
                    } else {
                        removed++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.error(
                            "Redis 예약 영속화 복구에 실패했습니다: {}",
                            member,
                            exception
                    );
                }
            }
        }

        return new ReservationRepairResult(
                scanned,
                repaired,
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
            clearPending(pending, member);
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
            clearPending(pending, member);
            return RepairOutcome.REMOVED;
        }

        clearPending(pending, member);
        return RepairOutcome.REPAIRED;
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

    private void clearPending(
            RedisKeyFactory.PendingReservationKey pending,
            String member
    ) {
        redisTemplate.opsForSet().remove(
                keyFactory.campaignReservationPersistencePending(
                        pending.campaignId()
                ),
                member
        );
        redisTemplate.opsForSet().remove(
                keyFactory.reservationPersistencePending(),
                member
        );
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
        REMOVED
    }
}
