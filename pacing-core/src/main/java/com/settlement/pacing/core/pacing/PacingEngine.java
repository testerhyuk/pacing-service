package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.campaign.Campaign;

import java.time.Duration;
import java.time.Instant;

public class PacingEngine {
    private final PacingRateCalculator pacingRateCalculator;
    private final PacingPolicyResolver pacingPolicyResolver;
    private final TargetSpendRateCalculatorResolver targetSpendRateCalculatorResolver;
    private final ActualSpendRateCalculator actualSpendRateCalculator;
    private final Duration pacingRateUpdateInterval;

    public PacingEngine(
            PacingRateCalculator pacingRateCalculator,
            PacingPolicyResolver pacingPolicyResolver,
            TargetSpendRateCalculatorResolver targetSpendRateCalculatorResolver,
            ActualSpendRateCalculator actualSpendRateCalculator,
            Duration pacingRateUpdateInterval
    ) {
        if (pacingRateCalculator == null
                || pacingPolicyResolver == null
                || targetSpendRateCalculatorResolver == null
                || actualSpendRateCalculator == null
                || pacingRateUpdateInterval == null) {
            throw new IllegalArgumentException("페이싱 엔진 구성 값은 null일 수 없습니다");
        }

        if (pacingRateUpdateInterval.isZero()
                || pacingRateUpdateInterval.isNegative()) {
            throw new IllegalArgumentException("페이싱 비율 갱신 주기는 0보다 커야 합니다");
        }

        this.pacingRateCalculator = pacingRateCalculator;
        this.pacingPolicyResolver = pacingPolicyResolver;
        this.targetSpendRateCalculatorResolver = targetSpendRateCalculatorResolver;
        this.actualSpendRateCalculator = actualSpendRateCalculator;
        this.pacingRateUpdateInterval = pacingRateUpdateInterval;
    }

    public PacingResult decide(
            PacingRequest request,
            Campaign campaign,
            BudgetState budgetState,
            PacingState pacingState,
            Rate sampleRate,
            PacingObservation observation,
            double ewmaAlpha
    ) {
        if (request == null
                ||campaign == null
                || budgetState == null
                || pacingState == null
                || sampleRate == null
                || observation == null
        ) {
            throw new IllegalArgumentException("페이싱 판단에 필요한 값은 null일 수 없습니다");
        }

        if (!request.campaignId().equals(campaign.campaignId())) {
            throw new IllegalArgumentException("페이싱 요청과 캠페인의 campaignId가 일치해야 합니다");
        }

        if (!campaign.campaignId().equals(budgetState.campaignId())) {
            throw new IllegalArgumentException("캠페인과 예산 상태의 campaignId가 일치해야 합니다");
        }

        Instant now = request.requestedAt();

        // 캠페인이 ACTIVE 상태가 아니면 차단한다.
        if (!campaign.isActive()) {
            PacingDecision decision = PacingDecision.block(
                    DecisionReason.CAMPAIGN_INACTIVE,
                    pacingState.pacingRate()
            );

            return new PacingResult(decision, pacingState);
        }

        // 현재 시각이 캠페인 집행 기간이 아니면 차단한다.
        if (!campaign.isWithinPeriodAt(now)) {
            PacingDecision decision = PacingDecision.block(
                    DecisionReason.OUTSIDE_CAMPAIGN_PERIOD,
                    pacingState.pacingRate()
            );

            return new PacingResult(decision, pacingState);
        }

        // 전체 예산이나 일일 예산이 소진되면 현재 상태를 유지하고 차단한다.
        if (budgetState.availableAmount().isZero()) {
            PacingDecision decision = PacingDecision.block(
                    DecisionReason.BUDGET_EXHAUSTED,
                    pacingState.pacingRate()
            );

            return new PacingResult(decision, pacingState);
        }

        TargetSpendRateCalculator targetCalculator =
                targetSpendRateCalculatorResolver.resolve(
                        campaign.pacingStrategy()
                );

        Rate targetSpendRate = targetCalculator.calculate(campaign, now);

        Rate actualSpendRate = actualSpendRateCalculator.calculate(budgetState);

        PacingState nextPacingState = pacingState;

        // 갱신 주기가 지난 경우에만 새로운 페이싱 비율을 계산한다.
        if (pacingState.shouldUpdateAt(
                now,
                pacingRateUpdateInterval
        )) {
            Instant nextTargetAt = now.plus(
                    pacingRateUpdateInterval
            );
            if (nextTargetAt.isAfter(campaign.endAt())) {
                nextTargetAt = campaign.endAt();
            }

            Rate nextTargetSpendRate =
                    targetCalculator.calculate(
                            campaign,
                            nextTargetAt
                    );

            Rate adjustedPacingRate =
                    pacingRateCalculator.calculate(
                            pacingState.pacingRate(),
                            budgetState,
                            targetSpendRate,
                            nextTargetSpendRate,
                            observation,
                            ewmaAlpha
                    );

            nextPacingState = new PacingState(
                    adjustedPacingRate,
                    now
            );
        }

        PacingContext context = new PacingContext(
                targetSpendRate,
                actualSpendRate,
                nextPacingState.pacingRate()
        );

        PacingPolicy pacingPolicy =
                pacingPolicyResolver.resolve(
                        campaign.pacingStrategy()
                );

        PacingDecision decision = pacingPolicy.decide(context, sampleRate);

        /*
         * 정책이 최종 판단에 적용한 비율과 저장할 비율이 다르면
         * 실제 적용된 비율을 새로운 페이싱 상태에 반영한다.
         */
        if (!decision.pacingRate().equals(
                nextPacingState.pacingRate()
        )) {
            nextPacingState = new PacingState(
                    decision.pacingRate(),
                    now
            );
        }

        return new PacingResult(
                decision,
                nextPacingState
        );
    }
}
