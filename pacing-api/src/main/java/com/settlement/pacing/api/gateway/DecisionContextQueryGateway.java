package com.settlement.pacing.api.gateway;

import java.time.LocalDate;
import java.util.Optional;

public interface DecisionContextQueryGateway {

    /**
     * 페이싱 판단에 필요한 Redis 상태를 한 번에 조회한다.
     *
     * Campaign, BudgetState, PacingState가 모두 Redis에 존재할 때만
     * Snapshot을 반환한다.
     *
     * 하나라도 준비되지 않은 경우 Optional.empty()를 반환하고,
     * 호출자가 기존 복구/초기화 경로를 사용한다.
     */
    Optional<DecisionContextSnapshot> find(
            String campaignId,
            LocalDate budgetDate
    );
}