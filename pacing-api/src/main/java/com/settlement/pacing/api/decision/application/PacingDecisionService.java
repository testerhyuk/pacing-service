package com.settlement.pacing.api.decision.application;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.decision.support.SampleRateGenerator;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.api.error.PacingStateUpdateException;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.pacing.*;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class PacingDecisionService {
    private final CampaignQueryGateway campaignQueryGateway;
    private final BudgetStateQueryGateway budgetStateQueryGateway;
    private final PacingStateGateway pacingStateGateway;
    private final PacingEngine pacingEngine;
    private final SampleRateGenerator sampleRateGenerator;
    private final PacingProperties pacingProperties;
    private final Clock clock;
    private final PacingApiMetrics pacingApiMetrics;

    public PacingDecisionResult decide(PacingDecisionCommand command) {
        Timer.Sample timerSample = pacingApiMetrics.startTimer();

        try {
            if (command == null) {
                throw new InvalidRequestException(
                        "command는 null일 수 없습니다"
                );
            }

            Campaign campaign = campaignQueryGateway.findById(command.campaignId()).orElseThrow(
                    () -> new CampaignNotFoundException(command.campaignId()));

            LocalDate budgetDate = command.requestedAt().atZone(pacingProperties.businessZoneId()).toLocalDate();

            BudgetState budgetState = budgetStateQueryGateway.find(command.campaignId(),  budgetDate).orElseThrow(
                    BudgetStateUnavailableException::new);

            Rate initialRate = pacingProperties.initialRate(campaign.pacingStrategy());
            PacingState initialPacingState = new PacingState(initialRate, command.requestedAt());

            PacingStateSnapshot pacingStateSnapshot = pacingStateGateway.getOrInitialize(command.campaignId(), initialPacingState);

            Rate sampleRate = sampleRateGenerator.generate(command.requestId(), command.campaignId());

            PacingRequest pacingRequest = new PacingRequest(command.requestId(), command.campaignId(), command.requestedAt());

            PacingDecisionResult result = decideWithRetry(
                    pacingRequest,
                    campaign,
                    budgetDate,
                    budgetState,
                    pacingStateSnapshot,
                    sampleRate
            );

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

    private PacingDecisionResult decideWithRetry(
            PacingRequest pacingRequest,
            Campaign campaign,
            LocalDate budgetDate,
            BudgetState initialBudgetState,
            PacingStateSnapshot initialSnapshot,
            Rate sampleRate
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
                    sampleRate
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
