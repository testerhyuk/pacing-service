package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventProcessor;
import com.settlement.pacing.core.billing.BillingResult;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingGateway;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingResult;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingStatus;
import com.settlement.pacing.worker.error.NonRetryableBillingEventException;
import com.settlement.pacing.worker.error.RetryableBillingEventException;

import java.util.Optional;

public class RedisBillingEventProcessingAdapter
        implements BillingEventProcessingGateway {
    private final BillingEventProcessor processor;
    private final BillingEventPersistenceService persistenceService;
    private final RedisBudgetStateStore budgetStateStore;
    private final BudgetStateRecoveryService recoveryService;
    private final RedisWorkerStateStore workerStateStore;

    public RedisBillingEventProcessingAdapter(
            BillingEventProcessor processor,
            BillingEventPersistenceService persistenceService,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            RedisWorkerStateStore workerStateStore
    ) {
        this.processor = processor;
        this.persistenceService = persistenceService;
        this.budgetStateStore = budgetStateStore;
        this.recoveryService = recoveryService;
        this.workerStateStore = workerStateStore;
    }

    @Override
    public BillingEventProcessingResult process(
            BillingEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "과금 이벤트는 null일 수 없습니다"
            );
        }

        Optional<BillingEventProcessingResult> duplicate =
                persistenceService.register(event);

        if (duplicate.isPresent()) {
            return duplicate.get();
        }

        BudgetReservationEntity entity =
                persistenceService.findReservation(
                        event.reservationId()
                ).orElseThrow(() ->
                        new RetryableBillingEventException(
                                "과금 대상 예약을 찾을 수 없습니다: "
                                        + event.reservationId()
                        )
                );

        Optional<RedisBillingTransition> alreadyApplied =
                workerStateStore.findAppliedBillingEvent(
                        entity.getCampaignId(),
                        event.eventId(),
                        event.reservationId()
                );

        if (alreadyApplied.isPresent()) {
            RedisBillingTransition transition =
                    alreadyApplied.get();
            persistenceService.complete(
                    event.eventId(),
                    transition
            );
            return result(
                    BillingEventProcessingStatus.DUPLICATE,
                    transition
            );
        }

        RedisReservationSnapshot reservationSnapshot =
                workerStateStore.getOrInitialize(entity);

        if (event.sequence()
                > reservationSnapshot.lastBillingSequence() + 1) {
            throw new RetryableBillingEventException(
                    "선행 과금 이벤트가 아직 처리되지 않았습니다: "
                            + event.reservationId()
                            + "/" + event.sequence()
            );
        }

        if (event.occurredAt().isBefore(
                reservationSnapshot.reservation().reservedAt()
        )) {
            throw new NonRetryableBillingEventException(
                    "과금 이벤트 시각은 예약 시각보다 이전일 수 없습니다"
            );
        }

        BudgetState currentBudget = currentBudget(
                reservationSnapshot.reservation().campaignId(),
                reservationSnapshot.reservation().budgetDate()
        );

        if (event.sequence()
                <= reservationSnapshot.lastBillingSequence()) {
            RedisBillingTransition transition = staleTransition(
                    event,
                    currentBudget,
                    reservationSnapshot
            );
            persistenceService.complete(
                    event.eventId(),
                    transition
            );
            return result(
                    BillingEventProcessingStatus.STALE,
                    transition
            );
        }

        BillingResult billingResult = processDomain(
                currentBudget,
                reservationSnapshot,
                event
        );

        RedisBillingTransition transition =
                workerStateStore.applyBillingEvent(
                        event,
                        currentBudget,
                        reservationSnapshot,
                        billingResult
                );

        persistenceService.complete(
                event.eventId(),
                transition
        );

        BillingEventProcessingStatus status = switch (
                transition.transitionStatus()
        ) {
            case APPLIED -> BillingEventProcessingStatus.APPLIED;
            case ALREADY_APPLIED ->
                    BillingEventProcessingStatus.DUPLICATE;
            case STALE -> BillingEventProcessingStatus.STALE;
        };

        return result(status, transition);
    }

    private BillingEventProcessingResult result(
            BillingEventProcessingStatus status,
            RedisBillingTransition transition
    ) {
        return new BillingEventProcessingResult(
                status,
                transition.eventId(),
                transition.reservationId(),
                transition.reservationStatus(),
                transition.appliedAmount(),
                transition.totalOverageAmount(),
                transition.dailyOverageAmount()
        );
    }

    private RedisBillingTransition staleTransition(
            BillingEvent event,
            BudgetState budgetState,
            RedisReservationSnapshot reservation
    ) {
        return new RedisBillingTransition(
                RedisBillingTransition.RedisTransitionStatus.STALE,
                event.eventId(),
                event.reservationId(),
                reservation.reservation().status(),
                reservation.appliedAmount(),
                reservation.version(),
                reservation.lastBillingSequence(),
                overage(
                        budgetState.totalEffectiveSpend(),
                        budgetState.totalBudget()
                ),
                overage(
                        budgetState.dailyEffectiveSpend(),
                        budgetState.dailyBudgetLimit()
                )
        );
    }

    private com.settlement.pacing.core.budget.Money overage(
            com.settlement.pacing.core.budget.Money effective,
            com.settlement.pacing.core.budget.Money limit
    ) {
        return limit.isLessThan(effective)
                ? effective.subtract(limit)
                : com.settlement.pacing.core.budget.Money.zero();
    }

    @Override
    public void markDeadLetter(
            String eventId,
            String reason
    ) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }

        persistenceService.markDeadLetter(eventId, reason);
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
                        "과금 처리에 필요한 예산 상태를 복구할 수 없습니다: "
                                + campaignId
                )
        );
    }

    private BillingResult processDomain(
            BudgetState budgetState,
            RedisReservationSnapshot reservation,
            BillingEvent event
    ) {
        try {
            return processor.process(
                    budgetState,
                    reservation.reservation(),
                    event,
                    reservation.appliedAmount()
            );
        } catch (ArithmeticException exception) {
            throw new NonRetryableBillingEventException(
                    "과금 금액 계산 범위를 초과했습니다",
                    exception
            );
        } catch (IllegalStateException
                 | IllegalArgumentException exception) {
            throw new NonRetryableBillingEventException(
                    "과금 이벤트가 현재 예약 상태와 일치하지 않습니다",
                    exception
            );
        }
    }
}
