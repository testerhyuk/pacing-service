package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import com.settlement.pacing.worker.expiration.application.ExpirationBatchResult;
import com.settlement.pacing.worker.expiration.application.ExpiredReservationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class RedisExpiredReservationAdapter
        implements ExpiredReservationGateway {
    private static final Logger log =
            LoggerFactory.getLogger(
                    RedisExpiredReservationAdapter.class
            );

    private final BillingEventPersistenceService persistenceService;
    private final RedisBudgetStateStore budgetStateStore;
    private final BudgetStateRecoveryService recoveryService;
    private final RedisWorkerStateStore workerStateStore;
    private final ExpirationClaimRepository claimRepository;
    private final Duration claimTtl;

    public RedisExpiredReservationAdapter(
            BillingEventPersistenceService persistenceService,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            RedisWorkerStateStore workerStateStore,
            ExpirationClaimRepository claimRepository,
            Duration claimTtl
    ) {
        this.persistenceService = persistenceService;
        this.budgetStateStore = budgetStateStore;
        this.recoveryService = recoveryService;
        this.workerStateStore = workerStateStore;
        this.claimRepository = claimRepository;
        this.claimTtl = claimTtl;
    }

    @Override
    public ExpirationBatchResult expire(
            Instant now,
            int batchSize
    ) {
        if (now == null) {
            throw new IllegalArgumentException(
                    "예약 만료 기준 시각은 null일 수 없습니다"
            );
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "예약 만료 batchSize는 0보다 커야 합니다"
            );
        }

        String claimToken = UUID.randomUUID().toString();
        List<String> candidates = claimRepository.claim(
                now,
                batchSize,
                claimToken,
                now.plus(claimTtl)
        );

        int expired = 0;
        int skipped = 0;
        int conflicts = 0;

        for (String reservationId : candidates) {
            try {
                CandidateResult result = expireOne(
                        reservationId,
                        now
                );

                switch (result) {
                    case EXPIRED -> expired++;
                    case SKIPPED -> skipped++;
                    case CONFLICT -> conflicts++;
                }
            } catch (RuntimeException exception) {
                log.error(
                        "만료 예약 처리에 실패했습니다: {}",
                        reservationId,
                        exception
                );
                conflicts++;
            } finally {
                releaseClaim(reservationId, claimToken);
            }
        }

        return new ExpirationBatchResult(
                candidates.size(),
                expired,
                skipped,
                conflicts
        );
    }

    private void releaseClaim(
            String reservationId,
            String claimToken
    ) {
        try {
            claimRepository.release(
                    reservationId,
                    claimToken
            );
        } catch (RuntimeException exception) {
            log.error(
                    "예약 만료 선점 해제에 실패했습니다: {}",
                    reservationId,
                    exception
            );
        }
    }

    private CandidateResult expireOne(
            String reservationId,
            Instant now
    ) {
        BudgetReservationEntity entity =
                persistenceService.findReservation(reservationId)
                        .orElse(null);

        if (entity == null
                || entity.getStatus()
                != ReservationStatus.RESERVED) {
            return CandidateResult.SKIPPED;
        }

        RedisReservationSnapshot snapshot =
                workerStateStore.getOrInitialize(entity);

        if (snapshot.reservation().status()
                != ReservationStatus.RESERVED) {
            persistenceService.synchronizeReservation(
                    snapshot.reservation().reservationId(),
                    snapshot.reservation().status(),
                    snapshot.appliedAmount(),
                    snapshot.version()
            );
            return CandidateResult.SKIPPED;
        }

        if (!snapshot.reservation().isExpiredAt(now)) {
            return CandidateResult.SKIPPED;
        }

        BudgetState currentBudget = currentBudget(
                snapshot.reservation().campaignId(),
                snapshot.reservation().budgetDate()
        );

        BudgetState nextBudget = currentBudget.release(
                snapshot.reservation().amount()
        );

        RedisExpirationTransition transition =
                workerStateStore.expire(
                        now,
                        currentBudget,
                        snapshot,
                        nextBudget
                );

        return switch (transition.status()) {
            case EXPIRED -> {
                persistenceService.synchronizeReservation(
                        reservationId,
                        ReservationStatus.EXPIRED,
                        snapshot.appliedAmount(),
                        transition.reservationVersion()
                );
                yield CandidateResult.EXPIRED;
            }
            case SKIPPED -> CandidateResult.SKIPPED;
            case CONFLICT -> CandidateResult.CONFLICT;
        };
    }

    private BudgetState currentBudget(
            String campaignId,
            java.time.LocalDate budgetDate
    ) {
        RedisBudgetStateStore.ReadResult current =
                budgetStateStore.read(campaignId, budgetDate);

        if (current.found()) {
            return current.budgetState();
        }

        return recoveryService.recover(
                campaignId,
                budgetDate
        ).orElseThrow(() ->
                new RetryableBillingEventException(
                        "예약 만료에 필요한 예산 상태를 복구할 수 없습니다: "
                                + campaignId
                )
        );
    }

    private enum CandidateResult {
        EXPIRED,
        SKIPPED,
        CONFLICT
    }
}
