package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.budget.ReservationStatus;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.budget.BudgetReservationJpaRepository;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingResult;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingStatus;
import com.settlement.pacing.worker.error.NonRetryableBillingEventException;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

public class BillingEventPersistenceService {
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final BillingEventJpaRepository eventRepository;
    private final BudgetReservationJpaRepository reservationRepository;
    private final Clock clock;

    public BillingEventPersistenceService(
            BillingEventJpaRepository eventRepository,
            BudgetReservationJpaRepository reservationRepository,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    @Transactional
    public Optional<BillingEventProcessingResult> register(
            BillingEvent event
    ) {
        Instant now = clock.instant();

        eventRepository.insertIfAbsent(
                event.eventId(),
                event.reservationId(),
                event.eventType().name(),
                event.targetAppliedAmount().amount(),
                event.sequence(),
                normalizedOccurredAt(event),
                now
        );

        BillingEventEntity stored = eventRepository.findById(
                event.eventId()
        ).orElseGet(() -> {
            Optional<BillingEventEntity> sequenceOwner =
                    eventRepository.findByReservationIdAndEventSequence(
                            event.reservationId(),
                            event.sequence()
                    );

            if (sequenceOwner.isPresent()) {
                throw new NonRetryableBillingEventException(
                        "동일 예약 순번에 서로 다른 과금 이벤트가 존재합니다: "
                                + event.reservationId()
                                + "/" + event.sequence()
                );
            }

            throw new RetryableBillingEventException(
                    "등록한 과금 이벤트를 PostgreSQL에서 조회할 수 없습니다: "
                            + event.eventId()
            );
        });

        validateSamePayload(stored, event);

        if (stored.getProcessingStatus()
                != BillingEventProcessingState.COMPLETED) {
            return Optional.empty();
        }

        return Optional.of(toDuplicateResult(stored));
    }

    public Optional<BudgetReservationEntity> findReservation(
            String reservationId
    ) {
        return reservationRepository.findById(reservationId);
    }

    @Transactional
    public void complete(
            String eventId,
            RedisBillingTransition transition
    ) {
        synchronizeReservation(transition);

        int updated = eventRepository.markCompleted(
                eventId,
                transition.reservationStatus().name(),
                transition.appliedAmount().amount(),
                transition.reservationVersion(),
                transition.totalOverageAmount().amount(),
                transition.dailyOverageAmount().amount(),
                clock.instant()
        );

        if (updated != 1) {
            throw new RetryableBillingEventException(
                    "과금 이벤트 완료 이력을 저장할 수 없습니다: "
                            + eventId
            );
        }
    }

    @Transactional
    public void synchronizeReservation(
            RedisBillingTransition transition
    ) {
        synchronizeReservation(
                transition.reservationId(),
                transition.reservationStatus(),
                transition.appliedAmount(),
                transition.reservationVersion(),
                transition.lastBillingSequence()
        );
    }

    @Transactional
    public void synchronizeReservation(
            String reservationId,
            ReservationStatus status,
            Money appliedAmount,
            long version,
            long lastBillingSequence
    ) {
        int updated = reservationRepository.updateFromRedis(
                reservationId,
                status.name(),
                appliedAmount.amount(),
                version,
                lastBillingSequence,
                clock.instant()
        );

        if (updated == 1) {
            return;
        }

        BudgetReservationEntity current =
                reservationRepository.findById(
                        reservationId
                ).orElseThrow(() -> new RetryableBillingEventException(
                        "동기화할 예약을 PostgreSQL에서 찾을 수 없습니다: "
                                + reservationId
                ));

        if (current.getVersion()
                < version) {
            throw new RetryableBillingEventException(
                    "Redis 예약 상태를 PostgreSQL에 반영하지 못했습니다: "
                            + reservationId
            );
        }

        if (current.getVersion()
                == version
                && (current.getStatus()
                != status
                || current.getAppliedAmount()
                != appliedAmount.amount()
                || current.getLastBillingSequence()
                != lastBillingSequence)) {
            throw new RetryableBillingEventException(
                    "동일 version의 Redis와 PostgreSQL 예약 상태가 다릅니다: "
                            + reservationId
            );
        }
    }

    @Transactional
    public void markDeadLetter(
            String eventId,
            String reason
    ) {
        eventRepository.markDeadLetter(
                eventId,
                truncate(reason),
                clock.instant()
        );
    }

    private void validateSamePayload(
            BillingEventEntity stored,
            BillingEvent event
    ) {
        boolean same = stored.getReservationId()
                .equals(event.reservationId())
                && stored.getEventType() == event.eventType()
                && stored.getTargetAppliedAmount()
                == event.targetAppliedAmount().amount()
                && stored.getEventSequence() == event.sequence()
                && stored.getOccurredAt()
                .equals(normalizedOccurredAt(event));

        if (!same) {
            throw new NonRetryableBillingEventException(
                    "동일 eventId에 서로 다른 과금 이벤트가 전달됐습니다: "
                            + event.eventId()
            );
        }
    }

    private BillingEventProcessingResult toDuplicateResult(
            BillingEventEntity stored
    ) {
        if (stored.getResultStatus() == null
                || stored.getResultAppliedAmount() == null
                || stored.getTotalOverageAmount() == null
                || stored.getDailyOverageAmount() == null) {
            throw new RetryableBillingEventException(
                    "완료된 과금 이벤트 결과가 누락됐습니다: "
                            + stored.getEventId()
            );
        }

        return new BillingEventProcessingResult(
                BillingEventProcessingStatus.DUPLICATE,
                stored.getEventId(),
                stored.getReservationId(),
                stored.getResultStatus(),
                new Money(stored.getResultAppliedAmount()),
                new Money(stored.getTotalOverageAmount()),
                new Money(stored.getDailyOverageAmount())
        );
    }

    private String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }

        return reason.length() <= MAX_FAILURE_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private Instant normalizedOccurredAt(BillingEvent event) {
        return event.occurredAt().truncatedTo(
                ChronoUnit.MICROS
        );
    }
}
