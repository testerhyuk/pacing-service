package com.settlement.pacing.api.reservation.application;

import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.api.monitoring.StorageAvailabilityMonitor;
import com.settlement.pacing.api.monitoring.StorageOperation;
import com.settlement.pacing.api.monitoring.StorageType;
import com.settlement.pacing.api.error.BudgetStateUnavailableException;
import com.settlement.pacing.api.error.CampaignNotReservableException;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.InsufficientBudgetException;
import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.api.error.ReservationConflictException;
import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.ReservationExecutionResult;
import com.settlement.pacing.api.gateway.ReservationExecutionStatus;
import com.settlement.pacing.api.gateway.PacingObservationGateway;
import com.settlement.pacing.core.budget.BudgetReservation;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BudgetReservationService {
    private final CampaignQueryGateway campaignQueryGateway;
    private final BudgetReservationGateway budgetReservationGateway;
    private final PacingObservationGateway pacingObservationGateway;
    private final PacingProperties pacingProperties;
    private final Clock clock;
    private final PacingApiMetrics pacingApiMetrics;
    private final StorageAvailabilityMonitor storageAvailabilityMonitor;

    public BudgetReservationResult reserve(BudgetReservationCommand command) {
        Timer.Sample timerSample = pacingApiMetrics.startTimer();
        boolean executionStatusRecorded = false;

        try {
            if (command == null) {
                throw new InvalidRequestException(
                        "command는 null일 수 없습니다"
                );
            }

            Instant now = clock.instant();
            Money amount = new Money(command.amount());

            Optional<BudgetReservation> existingReservation =
                    budgetReservationGateway.findById(
                            command.reservationId()
                    );
            storageAvailabilityMonitor.recordSuccess(
                    StorageType.POSTGRESQL,
                    StorageOperation.RESERVATION
            );

            if (existingReservation.isPresent()) {
                BudgetReservation existing = existingReservation.get();
                ReservationExecutionStatus existingStatus =
                        isSameReservation(existing, command, amount)
                                ? ReservationExecutionStatus.ALREADY_EXISTS
                                : ReservationExecutionStatus.CONFLICT;

                if (existingStatus == ReservationExecutionStatus.CONFLICT) {
                    pacingApiMetrics.recordPacingReservation(
                            timerSample,
                            existingStatus
                    );
                    executionStatusRecorded = true;
                    throw new ReservationConflictException();
                }

                pacingObservationGateway.recordReservation(
                        existing.reservationId(),
                        existing.campaignId(),
                        existing.amount(),
                        existing.reservedAt()
                );
                storageAvailabilityMonitor.recordSuccess(
                        StorageType.REDIS,
                        StorageOperation.RESERVATION
                );

                pacingApiMetrics.recordPacingReservation(
                        timerSample,
                        existingStatus
                );
                executionStatusRecorded = true;

                return BudgetReservationResult.existing(existing);
            }

            Campaign campaign = campaignQueryGateway.findById(command.campaignId()).orElseThrow(
                    () -> new CampaignNotFoundException(command.campaignId())
            );

            if (!campaign.isActive()) {
                throw CampaignNotReservableException.inactive(
                        command.campaignId()
                );
            }

            if (!campaign.isWithinPeriodAt(now)) {
                throw CampaignNotReservableException.outsidePeriod(
                        command.campaignId()
                );
            }

            LocalDate budgetDate = now.atZone(pacingProperties.businessZoneId()).toLocalDate();
            Instant expiresAt = now.plus(pacingProperties.reservationTtl());

            BudgetReservation reservation = new BudgetReservation(
                    command.reservationId(),
                    command.campaignId(),
                    budgetDate,
                    amount,
                    now,
                    expiresAt
            );

            ReservationExecutionResult executionResult = budgetReservationGateway.reserve(reservation);
            storageAvailabilityMonitor.recordSuccess(
                    StorageType.REDIS,
                    StorageOperation.RESERVATION
            );
            storageAvailabilityMonitor.recordSuccess(
                    StorageType.POSTGRESQL,
                    StorageOperation.RESERVATION
            );
            ReservationExecutionStatus executionStatus =
                    resolveExecutionStatus(
                            executionResult,
                            command,
                            amount
                    );

            if (executionStatus == ReservationExecutionStatus.CREATED
                    || executionStatus
                    == ReservationExecutionStatus.ALREADY_EXISTS) {
                BudgetReservation recordedReservation =
                        executionResult.reservation();
                pacingObservationGateway.recordReservation(
                        recordedReservation.reservationId(),
                        recordedReservation.campaignId(),
                        recordedReservation.amount(),
                        recordedReservation.reservedAt()
                );
            }

            pacingApiMetrics.recordPacingReservation(
                    timerSample,
                    executionStatus
            );
            executionStatusRecorded = true;

            return handleExecutionResult(
                    executionResult,
                    executionStatus
            );
        } catch (RuntimeException exception) {
            if (!executionStatusRecorded) {
                pacingApiMetrics.recordPacingReservationFailure(
                        timerSample,
                        exception
                );
            }

            throw exception;
        }
    }

    private ReservationExecutionStatus resolveExecutionStatus(
            ReservationExecutionResult executionResult,
            BudgetReservationCommand command,
            Money amount
    ) {
        if (executionResult.status()
                != ReservationExecutionStatus.ALREADY_EXISTS) {
            return executionResult.status();
        }

        return isSameReservation(
                executionResult.reservation(),
                command,
                amount
        )
                ? ReservationExecutionStatus.ALREADY_EXISTS
                : ReservationExecutionStatus.CONFLICT;
    }

    private boolean isSameReservation(
            BudgetReservation existing,
            BudgetReservationCommand command,
            Money amount
    ) {
        return existing.campaignId().equals(command.campaignId())
                && existing.amount().equals(amount);
    }

    private BudgetReservationResult handleExecutionResult(
            ReservationExecutionResult executionResult,
            ReservationExecutionStatus executionStatus
    ) {
        return switch (executionStatus) {
            case CREATED ->
                    BudgetReservationResult.created(
                            executionResult.reservation()
                    );

            case ALREADY_EXISTS ->
                    BudgetReservationResult.existing(
                            executionResult.reservation()
                    );

            case INSUFFICIENT_BUDGET ->
                    throw new InsufficientBudgetException();

            case CONFLICT ->
                    throw new ReservationConflictException();

            case BUDGET_STATE_NOT_FOUND ->
                    throw new BudgetStateUnavailableException();
        };
    }
}
