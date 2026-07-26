package com.settlement.pacing.infrastructure.budget;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class BudgetStateRecoveryQueryRepository {
    private final JdbcClient jdbcClient;

    public BudgetStateRecoveryQueryRepository(
            JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CampaignBudgetPolicy>
    findCampaignBudgetPolicy(String campaignId) {
        return jdbcClient.sql("""
                        SELECT campaign_id,
                               total_budget,
                               daily_budget_limit
                        FROM campaign
                        WHERE campaign_id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .query((resultSet, rowNumber) ->
                        new CampaignBudgetPolicy(
                                resultSet.getString(
                                        "campaign_id"
                                ),
                                resultSet.getLong(
                                        "total_budget"
                                ),
                                resultSet.getLong(
                                        "daily_budget_limit"
                                )
                        )
                )
                .optional();
    }

    public List<String> findCampaignIdsAfter(
            String campaignId,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "캠페인 조회 limit은 0보다 커야 합니다"
            );
        }

        return jdbcClient.sql("""
                        SELECT campaign_id
                        FROM campaign
                        WHERE campaign_id > :campaignId
                        ORDER BY campaign_id
                        LIMIT :limit
                        """)
                .param("campaignId", campaignId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public BudgetAggregate aggregate(
            String campaignId,
            LocalDate budgetDate
    ) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(
                                   CASE
                                       WHEN status = 'CONFIRMED'
                                       THEN applied_amount
                                       ELSE 0
                                   END
                               ), 0) AS total_spent_amount,
                               COALESCE(SUM(
                                   CASE
                                        WHEN status = 'RESERVED'
                                       THEN amount
                                       ELSE 0
                                   END
                               ), 0) AS total_reserved_amount,
                               COALESCE(SUM(
                                   CASE
                                       WHEN budget_date = :budgetDate
                                            AND status = 'CONFIRMED'
                                       THEN applied_amount
                                       ELSE 0
                                   END
                               ), 0) AS daily_spent_amount,
                               COALESCE(SUM(
                                   CASE
                                        WHEN budget_date = :budgetDate
                                             AND status = 'RESERVED'
                                       THEN amount
                                       ELSE 0
                                   END
                               ), 0) AS daily_reserved_amount
                        FROM budget_reservation
                        WHERE campaign_id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .param("budgetDate", budgetDate)
                .query((resultSet, rowNumber) ->
                        new BudgetAggregate(
                                resultSet.getLong(
                                        "total_spent_amount"
                                ),
                                resultSet.getLong(
                                        "total_reserved_amount"
                                ),
                                resultSet.getLong(
                                        "daily_spent_amount"
                                ),
                                resultSet.getLong(
                                        "daily_reserved_amount"
                                )
                        )
                )
                .single();
    }

    public record CampaignBudgetPolicy(
            String campaignId,
            long totalBudget,
            long dailyBudgetLimit
    ) {
    }

    public record BudgetAggregate(
            long totalSpentAmount,
            long totalReservedAmount,
            long dailySpentAmount,
            long dailyReservedAmount
    ) {
    }
}
