package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.ReservationExecutionResult;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class RedisBudgetReservationAdapter
        implements BudgetReservationGateway {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<List> reserveBudgetScript;
    private final RedisScript<Long> compensateReservationScript;
    private final BudgetStateQueryGateway budgetStateQueryGateway;
    private final ReservationPersistenceService persistenceService;
    private final PacingInfrastructureMetrics metrics;
    private final Clock clock;

    public RedisBudgetReservationAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<List> reserveBudgetScript,
            RedisScript<Long> compensateReservationScript,
            BudgetStateQueryGateway budgetStateQueryGateway,
            ReservationPersistenceService persistenceService,
            PacingInfrastructureMetrics metrics,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.reserveBudgetScript = reserveBudgetScript;
        this.compensateReservationScript =
                compensateReservationScript;
        this.budgetStateQueryGateway = budgetStateQueryGateway;
        this.persistenceService = persistenceService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Optional<BudgetReservation> findById(
            String reservationId
    ) {
        validateReservationId(reservationId);
        return persistenceService.findById(reservationId);
    }

    @Override
    public ReservationExecutionResult reserve(
            BudgetReservation reservation
    ) {
        if (reservation == null) {
            throw new IllegalArgumentException(
                    "예산 예약은 null일 수 없습니다"
            );
        }

        if (reservation.status() != ReservationStatus.RESERVED) {
            throw new IllegalArgumentException(
                    "새 예산 예약의 상태는 RESERVED여야 합니다"
            );
        }

        boolean budgetStateAvailable = budgetStateQueryGateway.find(
                reservation.campaignId(),
                reservation.budgetDate()
        ).isPresent();

        if (!budgetStateAvailable) {
            return result(
                    ReservationExecutionStatus
                            .BUDGET_STATE_NOT_FOUND,
                    null
            );
        }

        ScriptResult scriptResult = executeReserve(reservation);

        if (scriptResult.status()
                == ReservationExecutionStatus.CREATED) {
            return persistNewRedisReservation(
                    scriptResult.reservation()
            );
        }

        if (scriptResult.status()
                == ReservationExecutionStatus.ALREADY_EXISTS) {
            return persistExistingRedisReservation(
                    scriptResult.reservation()
            );
        }

        clearPersistencePending(reservation);
        return result(scriptResult.status(), null);
    }

    private ReservationExecutionResult persistNewRedisReservation(
            BudgetReservation storedInRedis
    ) {
        ReservationPersistenceService.InsertResult insertResult =
                persistenceService.insertIfAbsent(storedInRedis);

        if (insertResult.inserted()) {
            clearPersistencePending(storedInRedis);
            return result(
                    ReservationExecutionStatus.CREATED,
                    storedInRedis
            );
        }

        BudgetReservation existing = insertResult.reservation();

        if (sameIdentity(existing, storedInRedis)) {
            clearPersistencePending(storedInRedis);

            return result(
                    ReservationExecutionStatus.CREATED,
                    storedInRedis
            );
        }

        compensate(storedInRedis);
        clearPersistencePending(storedInRedis);

        return result(
                ReservationExecutionStatus.CONFLICT,
                null
        );
    }

    private ReservationExecutionResult
    persistExistingRedisReservation(
            BudgetReservation storedInRedis
    ) {
        ReservationPersistenceService.InsertResult insertResult =
                persistenceService.insertIfAbsent(storedInRedis);
        BudgetReservation persisted = insertResult.reservation();

        if (!sameIdentity(persisted, storedInRedis)) {
            compensate(storedInRedis);
            clearPersistencePending(storedInRedis);
            return result(
                    ReservationExecutionStatus.CONFLICT,
                    null
            );
        }

        clearPersistencePending(storedInRedis);
        return result(
                ReservationExecutionStatus.ALREADY_EXISTS,
                persisted
        );
    }

    private ScriptResult executeReserve(
            BudgetReservation reservation
    ) {
        List<?> rawResult = redisTemplate.execute(
                reserveBudgetScript,
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
                        ),
                        keyFactory.campaignReservationPersistencePending(
                                reservation.campaignId()
                        )
                ),
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.budgetDate().toString(),
                Long.toString(reservation.amount().amount()),
                Long.toString(
                        reservation.reservedAt().toEpochMilli()
                ),
                Long.toString(
                        reservation.expiresAt().toEpochMilli()
                ),
                keyFactory.reservationPersistenceMember(
                        reservation.campaignId(),
                        reservation.reservationId()
                ),
                Long.toString(clock.instant().toEpochMilli())
        );

        if (rawResult == null || rawResult.isEmpty()) {
            throw corrupted(
                    "Redis 예산 예약 실행 결과가 비어있습니다",
                    null
            );
        }

        ReservationExecutionStatus status;
        try {
            status = ReservationExecutionStatus.valueOf(
                    value(rawResult, 0)
            );
        } catch (IllegalArgumentException exception) {
            throw corrupted(
                    "알 수 없는 Redis 예산 예약 실행 결과입니다",
                    exception
            );
        }

        if (status != ReservationExecutionStatus.CREATED
                && status
                != ReservationExecutionStatus.ALREADY_EXISTS) {
            return new ScriptResult(status, null);
        }

        if (rawResult.size() != 8) {
            throw corrupted(
                    "Redis 예산 예약 결과의 필드 수가 올바르지 않습니다",
                    null
            );
        }

        try {
            BudgetReservation stored = new BudgetReservation(
                    value(rawResult, 1),
                    value(rawResult, 2),
                    LocalDate.parse(value(rawResult, 3)),
                    new Money(parsePositiveAmount(
                            rawResult,
                            4
                    )),
                    ReservationStatus.valueOf(
                            value(rawResult, 5)
                    ),
                    Instant.ofEpochMilli(Long.parseLong(
                            value(rawResult, 6)
                    )),
                    Instant.ofEpochMilli(Long.parseLong(
                            value(rawResult, 7)
                    ))
            );
            return new ScriptResult(status, stored);
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 예산 예약 값이 올바르지 않습니다",
                    exception
            );
        }
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
            throw new DataIntegrityViolationException(
                    "PostgreSQL 예약 충돌 후 Redis 예약 보정에 실패했습니다: "
                            + reservation.reservationId()
            );
        }
    }

    private void clearPersistencePending(
            BudgetReservation reservation
    ) {
        String member = keyFactory.reservationPersistenceMember(
                reservation.campaignId(),
                reservation.reservationId()
        );

        redisTemplate.opsForZSet().remove(
                keyFactory.campaignReservationPersistencePending(
                        reservation.campaignId()
                ),
                member
        );
    }

    private boolean sameIdentity(
            BudgetReservation left,
            BudgetReservation right
    ) {
        return left.campaignId().equals(right.campaignId())
                && left.amount().equals(right.amount());
    }

    private long parsePositiveAmount(
            List<?> result,
            int index
    ) {
        long amount = Long.parseLong(value(result, index));
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "예약 금액은 0보다 커야 합니다"
            );
        }
        return amount;
    }

    private String value(List<?> result, int index) {
        Object value = result.get(index);
        if (value == null) {
            throw corrupted(
                    "Redis 예산 예약 결과에 null이 포함됐습니다",
                    null
            );
        }
        return value.toString();
    }

    private ReservationExecutionResult result(
            ReservationExecutionStatus status,
            BudgetReservation reservation
    ) {
        metrics.recordReservation(status.name());
        return new ReservationExecutionResult(
                status,
                reservation
        );
    }

    private void validateReservationId(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException(
                    "reservationId는 null이거나 비어있을 수 없습니다"
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

    private record ScriptResult(
            ReservationExecutionStatus status,
            BudgetReservation reservation
    ) {
    }
}
