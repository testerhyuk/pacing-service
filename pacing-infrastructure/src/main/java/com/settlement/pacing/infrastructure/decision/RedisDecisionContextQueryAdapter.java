package com.settlement.pacing.infrastructure.decision;

import com.settlement.pacing.api.gateway.DecisionContextQueryGateway;
import com.settlement.pacing.api.gateway.DecisionContextSnapshot;
import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.core.campaign.Campaign;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.Rate;
import com.settlement.pacing.infrastructure.campaign.RedisCampaignCache;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.pacing.PacingStateSnapshotPersistenceCoordinator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class RedisDecisionContextQueryAdapter
        implements DecisionContextQueryGateway {

    private static final int FOUND_RESULT_SIZE = 17;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<List> readDecisionContextScript;
    private final RedisCampaignCache campaignCache;

    private final PacingStateSnapshotPersistenceCoordinator
            snapshotPersistenceCoordinator;

    public RedisDecisionContextQueryAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<List> readDecisionContextScript,
            RedisCampaignCache campaignCache,
            PacingStateSnapshotPersistenceCoordinator
                    snapshotPersistenceCoordinator
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.readDecisionContextScript =
                readDecisionContextScript;
        this.campaignCache = campaignCache;
        this.snapshotPersistenceCoordinator =
                snapshotPersistenceCoordinator;
    }

    @Override
    public Optional<DecisionContextSnapshot> find(
            String campaignId,
            LocalDate budgetDate
    ) {
        validate(campaignId, budgetDate);

        List<?> result = redisTemplate.execute(
                readDecisionContextScript,
                List.of(
                        keyFactory.campaign(campaignId),
                        keyFactory.totalBudget(campaignId),
                        keyFactory.dailyBudget(
                                campaignId,
                                budgetDate
                        ),
                        keyFactory.pacingState(campaignId)
                )
        );

        if (result == null || result.isEmpty()) {
            throw corrupted(
                    "Redis 판단 컨텍스트 조회 결과가 비어있습니다",
                    null
            );
        }

        String status = value(result, 0);

        switch (status) {
            case "MISSING_CAMPAIGN",
                 "MISSING_TOTAL_BUDGET",
                 "MISSING_DAILY_BUDGET",
                 "MISSING_PACING_STATE" -> {
                /*
                 * Cache miss는 장애가 아니다.
                 *
                 * PacingDecisionService가 기존 Gateway를 사용해
                 * PostgreSQL 복구 / Redis 초기화를 수행한다.
                 */
                return Optional.empty();
            }

            case "CORRUPTED_CAMPAIGN" -> {
                /*
                 * 기존 RedisCampaignCache.find()도
                 * 잘못된 Campaign cache는 제거한 뒤
                 * PostgreSQL에서 다시 적재한다.
                 *
                 * 동일한 의미를 유지한다.
                 */
                campaignCache.evict(campaignId);
                return Optional.empty();
            }

            case "CORRUPTED_TOTAL_BUDGET",
                 "CORRUPTED_DAILY_BUDGET" -> throw corrupted(
                    "Redis 예산 상태 Hash의 필수 필드가 누락됐습니다",
                    null
            );

            case "CORRUPTED_PACING_STATE" -> throw corrupted(
                    "Redis 페이싱 상태 Hash의 필수 필드가 누락됐습니다",
                    null
            );

            case "FOUND" -> {
                // 아래에서 파싱
            }

            default -> throw corrupted(
                    "알 수 없는 Redis 판단 컨텍스트 상태입니다: "
                            + status,
                    null
            );
        }

        if (result.size() != FOUND_RESULT_SIZE) {
            throw corrupted(
                    "Redis 판단 컨텍스트 필드 수가 올바르지 않습니다",
                    null
            );
        }

        Optional<Campaign> campaign =
                parseCampaign(
                        campaignId,
                        result
                );

        if (campaign.isEmpty()) {
            return Optional.empty();
        }

        BudgetState budgetState = parseBudgetState(
                campaignId,
                budgetDate,
                result
        );

        PacingStateSnapshot pacingStateSnapshot =
                parsePacingState(result);

        /*
         * 기존 PacingStateGateway.getOrInitialize()가 하던
         * PostgreSQL snapshot 영속화 의미를 유지한다.
         */
        snapshotPersistenceCoordinator.persistIfNeeded(
                campaignId,
                pacingStateSnapshot
        );

        return Optional.of(
                new DecisionContextSnapshot(
                        campaign.get(),
                        budgetState,
                        pacingStateSnapshot
                )
        );
    }

    private Optional<Campaign> parseCampaign(
            String campaignId,
            List<?> result
    ) {
        try {
            return Optional.of(
                    new Campaign(
                            value(result, 1),
                            CampaignStatus.valueOf(
                                    value(result, 2)
                            ),
                            Instant.ofEpochMilli(
                                    Long.parseLong(
                                            value(result, 3)
                                    )
                            ),
                            Instant.ofEpochMilli(
                                    Long.parseLong(
                                            value(result, 4)
                                    )
                            ),
                            PacingStrategy.valueOf(
                                    value(result, 5)
                            )
                    )
            );
        } catch (RuntimeException exception) {
            campaignCache.evict(campaignId);
            return Optional.empty();
        }
    }

    private BudgetState parseBudgetState(
            String campaignId,
            LocalDate budgetDate,
            List<?> result
    ) {
        try {
            long totalBudget =
                    parseNonNegativeLong(result, 6);
            long totalSpent =
                    parseNonNegativeLong(result, 7);
            long totalReserved =
                    parseNonNegativeLong(result, 8);

            /*
             * 현재 판단에서는 version을 사용하지 않지만
             * 기존 RedisBudgetStateStore와 동일하게 검증한다.
             */
            parseNonNegativeLong(result, 9);

            long dailyBudgetLimit =
                    parseNonNegativeLong(result, 10);
            long dailySpent =
                    parseNonNegativeLong(result, 11);
            long dailyReserved =
                    parseNonNegativeLong(result, 12);

            parseNonNegativeLong(result, 13);

            return new BudgetState(
                    campaignId,
                    budgetDate,
                    new Money(totalBudget),
                    new Money(totalSpent),
                    new Money(totalReserved),
                    new Money(dailyBudgetLimit),
                    new Money(dailySpent),
                    new Money(dailyReserved)
            );
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 예산 상태 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    private PacingStateSnapshot parsePacingState(
            List<?> result
    ) {
        try {
            double pacingRate = Double.parseDouble(
                    value(result, 14)
            );

            long updatedAtEpochMillis = Long.parseLong(
                    value(result, 15)
            );

            long version = parseNonNegativeLong(
                    result,
                    16
            );

            return new PacingStateSnapshot(
                    new PacingState(
                            new Rate(pacingRate),
                            Instant.ofEpochMilli(
                                    updatedAtEpochMillis
                            )
                    ),
                    version
            );
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 페이싱 상태 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    private long parseNonNegativeLong(
            List<?> result,
            int index
    ) {
        long parsed = Long.parseLong(
                value(result, index)
        );

        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "Redis 숫자 값은 음수일 수 없습니다"
            );
        }

        return parsed;
    }

    private String value(
            List<?> result,
            int index
    ) {
        Object value = result.get(index);

        if (value == null) {
            throw corrupted(
                    "Redis 판단 컨텍스트 값이 null입니다",
                    null
            );
        }

        return value.toString();
    }

    private void validate(
            String campaignId,
            LocalDate budgetDate
    ) {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException(
                    "campaignId는 null이거나 비어있을 수 없습니다"
            );
        }

        if (budgetDate == null) {
            throw new IllegalArgumentException(
                    "budgetDate는 null일 수 없습니다"
            );
        }
    }

    private DataIntegrityViolationException corrupted(
            String message,
            Throwable cause
    ) {
        return cause == null
                ? new DataIntegrityViolationException(message)
                : new DataIntegrityViolationException(
                message,
                cause
        );
    }
}