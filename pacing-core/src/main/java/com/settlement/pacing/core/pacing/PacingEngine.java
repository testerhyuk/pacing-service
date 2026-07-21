package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.campaign.Campaign;

import java.time.Instant;

public class PacingEngine {
    private final PacingRateCalculator pacingRateCalculator;
    private final PacingPolicyResolver pacingPolicyResolver;

    public PacingEngine(
            PacingRateCalculator pacingRateCalculator,
            PacingPolicyResolver pacingPolicyResolver
    ) {
        if (pacingRateCalculator == null || pacingPolicyResolver == null) {
            throw new IllegalArgumentException("페이싱 계산기와 정책 선택기는 null일 수 없습니다");
        }

        this.pacingRateCalculator = pacingRateCalculator;
        this.pacingPolicyResolver = pacingPolicyResolver;
    }

    public PacingDecision decide(
            Campaign campaign,
            BudgetState budgetState,
            PacingContext context,
            Rate sampleRate,
            Instant now
    ) {
        if (campaign == null
                || budgetState == null
                || context == null
                || sampleRate == null
                || now == null) {
            throw new IllegalArgumentException("페이싱 판단에 필요한 값은 null일 수 없습니다");
        }

        // 캠페인이 중지됐거나 운영 기간이 아니면 차단
        if (!campaign.isActiveAt(now)) {
            return PacingDecision.block();
        }

        // 사용할 수 있는 예산이 모두 소진됐으면 차단
        if (budgetState.availableAmount().amount() == 0L) {
            return PacingDecision.block();
        }

        // 목표 소진율과 실제 소진율 차이를 이용해 통과율 조정
        Rate adjustedPacingRate = pacingRateCalculator.calculate(
                context.pacingRate(),
                context.targetSpendRate(),
                context.actualSpendRate()
        );

        // 기존 컨텍스트에 조정된 통과율을 반영
        PacingContext adjustedContext = new PacingContext(
                context.targetSpendRate(),
                context.actualSpendRate(),
                adjustedPacingRate,
                context.trafficPeriod()
        );

        // 캠페인 전략에 맞는 정책 선택
        PacingPolicy pacingPolicy = pacingPolicyResolver.resolve(campaign.pacingStrategy());

        // 개별 광고 후보 최종 판단
        return pacingPolicy.decide(adjustedContext, sampleRate);
    }
}
