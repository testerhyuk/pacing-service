package com.settlement.pacing.infrastructure.campaign;

import com.settlement.pacing.api.error.BudgetLimitConflictException;
import com.settlement.pacing.api.gateway.CampaignManagementGateway;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore.LimitUpdateResult;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore.LimitUpdateStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public class CampaignManagementAdapter
        implements CampaignManagementGateway {
    private final CampaignJpaRepository repository;
    private final RedisCampaignCache campaignCache;
    private final RedisBudgetStateStore budgetStateStore;

    public CampaignManagementAdapter(
            CampaignJpaRepository repository,
            RedisCampaignCache campaignCache,
            RedisBudgetStateStore budgetStateStore
    ) {
        this.repository = repository;
        this.campaignCache = campaignCache;
        this.budgetStateStore = budgetStateStore;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CampaignSettings> findById(String campaignId) {
        return repository.findById(campaignId)
                .map(this::toSettings);
    }

    @Override
    @Transactional
    public CampaignSettings save(
            CampaignSettings settings,
            LocalDate budgetDate
    ) {
        Optional<CampaignSettings> previous =
                repository.findById(settings.campaignId())
                        .map(this::toSettings);

        LimitUpdateResult limitUpdate =
                budgetStateStore.updateLimits(
                        settings.campaignId(),
                        budgetDate,
                        settings.totalBudget(),
                        settings.dailyBudgetLimit()
                );
        validateLimitUpdate(limitUpdate);

        try {
            CampaignEntity saved = repository.saveAndFlush(
                    toEntity(settings)
            );
            campaignCache.evict(settings.campaignId());
            return toSettings(saved);
        } catch (RuntimeException exception) {
            restorePreviousLimits(
                    previous,
                    budgetDate,
                    limitUpdate,
                    exception
            );
            throw exception;
        }
    }

    private void validateLimitUpdate(LimitUpdateResult result) {
        switch (result.status()) {
            case UPDATED, MISSING -> {
            }
            case INSUFFICIENT_TOTAL ->
                    throw new BudgetLimitConflictException(
                            "전체 예산을 현재 누적 소진·예약액보다 작게 변경할 수 없습니다"
                    );
            case INSUFFICIENT_DAILY ->
                    throw new BudgetLimitConflictException(
                            "일일 예산을 오늘의 소진·예약액보다 작게 변경할 수 없습니다"
                    );
            case INVALID_LIMIT ->
                    throw new IllegalArgumentException(
                            "예산 한도가 올바르지 않습니다"
                    );
            case CORRUPTED ->
                    throw new DataIntegrityViolationException(
                            "Redis 예산 상태가 손상되어 예산을 변경할 수 없습니다"
                    );
        }
    }

    private void restorePreviousLimits(
            Optional<CampaignSettings> previous,
            LocalDate budgetDate,
            LimitUpdateResult update,
            RuntimeException original
    ) {
        if (previous.isEmpty()
                || update.status() != LimitUpdateStatus.UPDATED) {
            return;
        }

        try {
            budgetStateStore.updateLimits(
                    previous.get().campaignId(),
                    budgetDate,
                    update.previousTotalBudget(),
                    update.previousDailyBudgetLimit() >= 0
                            ? update.previousDailyBudgetLimit()
                            : previous.get().dailyBudgetLimit()
            );
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
    }

    private CampaignSettings toSettings(CampaignEntity entity) {
        return new CampaignSettings(
                entity.getCampaignId(),
                entity.getStatus(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getPacingStrategy(),
                entity.getTotalBudget(),
                entity.getDailyBudgetLimit(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private CampaignEntity toEntity(CampaignSettings settings) {
        return new CampaignEntity(
                settings.campaignId(),
                settings.status(),
                settings.startAt(),
                settings.endAt(),
                settings.pacingStrategy(),
                settings.totalBudget(),
                settings.dailyBudgetLimit(),
                settings.createdAt(),
                settings.updatedAt()
        );
    }
}
