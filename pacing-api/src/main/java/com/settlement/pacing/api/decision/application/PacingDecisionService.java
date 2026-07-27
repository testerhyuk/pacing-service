package com.settlement.pacing.api.decision.application;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.decision.support.SampleRateGenerator;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.api.error.PacingStateUpdateException;
import com.settlement.pacing.api.gateway.*;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.pacing.*;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PacingDecisionService {
    private final CampaignQueryGateway campaignQueryGateway;
    private final BudgetStateQueryGateway budgetStateQueryGateway;
    private final PacingStateGateway pacingStateGateway;
    private final PacingObservationGateway pacingObservationGateway;
    private final PacingEngine pacingEngine;
    private final SampleRateGenerator sampleRateGenerator;
    private final PacingProperties pacingProperties;
    private final Clock clock;
    private final PacingApiMetrics pacingApiMetrics;
    private final DecisionContextQueryGateway decisionContextQueryGateway;

    public PacingDecisionResult decide(PacingDecisionCommand command) {
        Timer.Sample timerSample = pacingApiMetrics.startTimer();

        try {
            if (command == null) {
                throw new InvalidRequestException(
                        "command는 null일 수 없습니다"
                );
            }

            Instant processingAt = clock.instant();
            validateRequestTime(command.requestedAt(), processingAt);

            LocalDate budgetDate = processingAt
                    .atZone(
                            pacingProperties.businessZoneId()
                    )
                    .toLocalDate();

            DecisionContextSnapshot context =
                    loadDecisionContext(
                            command.campaignId(),
                            budgetDate,
                            processingAt
                    );

            Campaign campaign = context.campaign();

            BudgetState budgetState =
                    context.budgetState();

            PacingStateSnapshot pacingStateSnapshot =
                    context.pacingStateSnapshot();

            Rate sampleRate = sampleRateGenerator.generate(command.requestId(), command.campaignId());

            PacingObservation observation =
                    pacingStateSnapshot.pacingState()
                            .shouldUpdateAt(
                                    processingAt,
                                    pacingProperties
                                            .rateUpdateInterval()
                            )
                            ? pacingObservationGateway.recent(
                                    command.campaignId(),
                                    processingAt
                            )
                            : PacingObservation.empty();

            PacingRequest pacingRequest = new PacingRequest(
                    command.requestId(),
                    command.campaignId(),
                    processingAt
            );

            PacingDecisionResult result = decideWithRetry(
                    pacingRequest,
                    campaign,
                    budgetDate,
                    budgetState,
                    pacingStateSnapshot,
                    sampleRate,
                    observation
            );

            if (result.reason() == DecisionReason.PASS
                    || result.reason()
                    == DecisionReason.PACING_REJECTED) {
                pacingObservationGateway.recordDecision(
                        result.requestId(),
                        result.campaignId(),
                        result.decision(),
                        processingAt
                );
            }

            pacingApiMetrics.recordPacingDecision(
                    timerSample,
                    campaign.pacingStrategy(),
                    result.decision(),
                    result.reason()
            );

            return result;
        } catch (RuntimeException exception) {
            pacingApiMetrics.recordPacingDecisionFailure(
                    timerSample,
                    exception
            );

            throw exception;
        }
    }

    private DecisionContextSnapshot loadDecisionContext(
            String campaignId,
            LocalDate budgetDate,
            Instant processingAt
    ) {
        Optional<DecisionContextSnapshot> context =
                decisionContextQueryGateway.find(
                        campaignId,
                        budgetDate
                );

        if (context.isPresent()) {
            return context.get();
        }

        /*
         * Redis 통합 조회에서 하나라도 준비되지 않았다면
         * 기존 복구 경로를 그대로 사용한다.
         */

        Campaign campaign = campaignQueryGateway
                .findById(campaignId)
                .orElseThrow(
                        () -> new CampaignNotFoundException(
                                campaignId
                        )
                );

        BudgetState budgetState =
                budgetStateQueryGateway
                        .find(
                                campaignId,
                                budgetDate
                        )
                        .orElseThrow(
                                BudgetStateUnavailableException::new
                        );

        Rate initialRate =
                pacingProperties.initialRate(
                        campaign.pacingStrategy()
                );

        PacingState initialPacingState =
                new PacingState(
                        initialRate,
                        processingAt
                );

        PacingStateSnapshot pacingStateSnapshot =
                pacingStateGateway.getOrInitialize(
                        campaignId,
                        initialPacingState
                );

        return new DecisionContextSnapshot(
                campaign,
                budgetState,
                pacingStateSnapshot
        );
    }

    private void validateRequestTime(
            Instant requestedAt,
            Instant processingAt
    ) {
        Duration difference = Duration.between(
                requestedAt,
                processingAt
        ).abs();

        if (difference.compareTo(
                pacingProperties.requestTimeTolerance()
        ) > 0) {
            throw new InvalidRequestException(
                    "requestedAt이 서버 시각의 허용 범위를 벗어났습니다"
            );
        }
    }

    private PacingDecisionResult decideWithRetry(
            PacingRequest pacingRequest,
            Campaign campaign,
            LocalDate budgetDate,
            BudgetState initialBudgetState,
            PacingStateSnapshot initialSnapshot,
            Rate sampleRate,
            PacingObservation observation
    ) {
        BudgetState currentBudgetState = initialBudgetState;
        PacingStateSnapshot currentSnapshot = initialSnapshot;

        int maxRetries = pacingProperties.stateUpdateMaxRetries();

        for (int retryCount = 0; retryCount <= maxRetries; retryCount++) {
            PacingResult pacingResult = pacingEngine.decide(
                    pacingRequest,
                    campaign,
                    currentBudgetState,
                    currentSnapshot.pacingState(),
                    sampleRate,
                    observation
            );

            if (pacingResult.pacingState().equals(
                    currentSnapshot.pacingState()
            )) {
                return PacingDecisionResult.from(
                        pacingRequest,
                        pacingResult,
                        clock.instant()
                );
            }

            boolean updated = pacingStateGateway.compareAndSet(
                    campaign.campaignId(),
                    currentSnapshot.version(),
                    pacingResult.pacingState()
            );

            if (updated) {
                return PacingDecisionResult.from(
                        pacingRequest,
                        pacingResult,
                        clock.instant()
                );
            }

            pacingApiMetrics.recordPacingStateConflict();

            if (retryCount == maxRetries) {
                throw new PacingStateUpdateException(
                        campaign.campaignId()
                );
            }

            currentSnapshot = pacingStateGateway
                    .findByCampaignId(campaign.campaignId())
                    .orElseThrow(
                            () -> new PacingStateUpdateException(
                                    campaign.campaignId()
                            )
                    );

            currentBudgetState = budgetStateQueryGateway
                    .find(
                            campaign.campaignId(),
                            budgetDate
                    )
                    .orElseThrow(
                            BudgetStateUnavailableException::new
                    );
        }

        throw new PacingStateUpdateException(
                campaign.campaignId()
        );
    }
}
