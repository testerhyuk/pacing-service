package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import org.springframework.dao.DataIntegrityViolationException;

public class BudgetReservationMapper {

    public BudgetReservation toDomain(
            BudgetReservationEntity entity
    ) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "BudgetReservationEntity는 null일 수 없습니다"
            );
        }

        try {
            return new BudgetReservation(
                    entity.getReservationId(),
                    entity.getCampaignId(),
                    entity.getBudgetDate(),
                    new Money(entity.getAmount()),
                    entity.getStatus(),
                    entity.getReservedAt(),
                    entity.getExpiresAt()
            );
        } catch (IllegalArgumentException exception) {
            throw new DataIntegrityViolationException(
                    "저장된 예산 예약 데이터가 올바르지 않습니다: "
                            + entity.getReservationId(),
                    exception
            );
        }
    }
}
