package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.core.budget.BudgetReservation;

import java.time.Clock;
import java.util.Optional;

public class ReservationPersistenceService {
    private final BudgetReservationJpaRepository repository;
    private final BudgetReservationMapper mapper;
    private final Clock clock;

    public ReservationPersistenceService(
            BudgetReservationJpaRepository repository,
            BudgetReservationMapper mapper,
            Clock clock
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<BudgetReservation> findById(
            String reservationId
    ) {
        return repository.findById(reservationId)
                .map(mapper::toDomain);
    }

    public InsertResult insertIfAbsent(
            BudgetReservation reservation
    ) {
        int inserted = repository.insertIfAbsent(
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.budgetDate(),
                reservation.amount().amount(),
                reservation.reservedAt(),
                reservation.expiresAt(),
                clock.instant()
        );

        if (inserted == 1) {
            return new InsertResult(true, reservation);
        }

        BudgetReservation existing = findById(
                reservation.reservationId()
        ).orElseThrow(() -> new IllegalStateException(
                "예약 insert 충돌 후 기존 예약을 조회할 수 없습니다: "
                        + reservation.reservationId()
        ));

        return new InsertResult(false, existing);
    }

    public record InsertResult(
            boolean inserted,
            BudgetReservation reservation
    ) {
    }
}
