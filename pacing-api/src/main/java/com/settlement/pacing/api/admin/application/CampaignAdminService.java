package com.settlement.pacing.api.admin.application;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.error.CampaignNotFoundException;
import com.settlement.pacing.api.error.InvalidRequestException;
import com.settlement.pacing.api.gateway.CampaignManagementGateway;
import com.settlement.pacing.api.gateway.CampaignManagementGateway.CampaignSettings;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class CampaignAdminService {
    private final CampaignManagementGateway managementGateway;
    private final PacingStateGateway pacingStateGateway;
    private final PacingProperties properties;
    private final AuditLogger auditLogger;
    private final Clock clock;

    public CampaignAdminService(
            CampaignManagementGateway managementGateway,
            PacingStateGateway pacingStateGateway,
            PacingProperties properties,
            AuditLogger auditLogger,
            Clock clock
    ) {
        this.managementGateway = managementGateway;
        this.pacingStateGateway = pacingStateGateway;
        this.properties = properties;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    public CampaignSettings find(String campaignId) {
        return managementGateway.findById(campaignId)
                .orElseThrow(() ->
                        new CampaignNotFoundException(campaignId)
                );
    }

    public CampaignSettings upsert(
            UpsertCampaignCommand command,
            String clientId,
            String requestId
    ) {
        validate(command);

        Instant now = clock.instant();
        Optional<CampaignSettings> before =
                managementGateway.findById(command.campaignId());
        Instant createdAt = before
                .map(CampaignSettings::createdAt)
                .orElse(now);

        CampaignSettings requested = new CampaignSettings(
                command.campaignId(),
                command.status(),
                command.startAt(),
                command.endAt(),
                command.pacingStrategy(),
                command.totalBudget(),
                command.dailyBudgetLimit(),
                createdAt,
                now
        );
        LocalDate budgetDate = now
                .atZone(properties.businessZoneId())
                .toLocalDate();
        CampaignSettings saved = managementGateway.save(
                requested,
                budgetDate
        );

        if (before.isEmpty()
                || before.get().pacingStrategy()
                != saved.pacingStrategy()) {
            pacingStateGateway.delete(saved.campaignId());
        }

        recordChanges(
                before.orElse(null),
                saved,
                clientId,
                requestId,
                now
        );
        return saved;
    }

    private void validate(UpsertCampaignCommand command) {
        if (command == null) {
            throw new InvalidRequestException(
                    "캠페인 변경 명령은 null일 수 없습니다"
            );
        }
        if (command.campaignId() == null
                || command.campaignId().isBlank()) {
            throw new InvalidRequestException(
                    "campaignId는 비어있을 수 없습니다"
            );
        }
        if (command.status() == null
                || command.startAt() == null
                || command.endAt() == null
                || command.pacingStrategy() == null) {
            throw new InvalidRequestException(
                    "캠페인 필수 값은 null일 수 없습니다"
            );
        }
        if (!command.startAt().isBefore(command.endAt())) {
            throw new InvalidRequestException(
                    "캠페인 시작 시각은 종료 시각보다 이전이어야 합니다"
            );
        }
        if (command.totalBudget() < 0
                || command.dailyBudgetLimit() < 0
                || command.dailyBudgetLimit()
                > command.totalBudget()) {
            throw new InvalidRequestException(
                    "일일 예산은 0 이상이며 전체 예산 이하여야 합니다"
            );
        }
    }

    private void recordChanges(
            CampaignSettings before,
            CampaignSettings after,
            String clientId,
            String requestId,
            Instant occurredAt
    ) {
        log(
                AuditLogger.EventType.CAMPAIGN_CHANGE,
                before,
                after,
                clientId,
                requestId,
                occurredAt
        );

        if (before == null
                || before.totalBudget() != after.totalBudget()
                || before.dailyBudgetLimit()
                != after.dailyBudgetLimit()) {
            log(
                    AuditLogger.EventType.BUDGET_CHANGE,
                    before,
                    after,
                    clientId,
                    requestId,
                    occurredAt
            );
        }

        if (before == null
                || before.pacingStrategy()
                != after.pacingStrategy()) {
            log(
                    AuditLogger.EventType.PACING_STRATEGY_CHANGE,
                    before,
                    after,
                    clientId,
                    requestId,
                    occurredAt
            );
        }
    }

    private void log(
            AuditLogger.EventType type,
            CampaignSettings before,
            CampaignSettings after,
            String clientId,
            String requestId,
            Instant occurredAt
    ) {
        auditLogger.log(new AuditLogger.AuditEvent(
                type,
                clientId,
                requestId,
                after.campaignId(),
                before == null ? null : before.toString(),
                after.toString(),
                AuditLogger.Result.SUCCESS,
                null,
                occurredAt
        ));
    }
}
