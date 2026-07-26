package com.settlement.pacing.infrastructure.worker;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;

public class BudgetReconciliationRepository {
    private final JdbcClient jdbcClient;

    public BudgetReconciliationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(
            String campaignId,
            LocalDate budgetDate,
            long ledgerTotalSpentAmount,
            long ledgerTotalReservedAmount,
            long redisTotalSpentAmount,
            long redisTotalReservedAmount,
            long ledgerSpentAmount,
            long ledgerReservedAmount,
            long redisSpentAmount,
            long redisReservedAmount,
            long mismatchAmount,
            String status,
            Instant reconciledAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO budget_reconciliation (
                            campaign_id,
                            budget_date,
                            ledger_total_spent_amount,
                            ledger_total_reserved_amount,
                            redis_total_spent_amount,
                            redis_total_reserved_amount,
                            ledger_spent_amount,
                            ledger_reserved_amount,
                            redis_spent_amount,
                            redis_reserved_amount,
                            mismatch_amount,
                            status,
                            reconciled_at
                        )
                        VALUES (
                            :campaignId,
                            :budgetDate,
                            :ledgerTotalSpentAmount,
                            :ledgerTotalReservedAmount,
                            :redisTotalSpentAmount,
                            :redisTotalReservedAmount,
                            :ledgerSpentAmount,
                            :ledgerReservedAmount,
                            :redisSpentAmount,
                            :redisReservedAmount,
                            :mismatchAmount,
                            :status,
                            :reconciledAt
                        )
                        """)
                .param("campaignId", campaignId)
                .param("budgetDate", budgetDate)
                .param(
                        "ledgerTotalSpentAmount",
                        ledgerTotalSpentAmount
                )
                .param(
                        "ledgerTotalReservedAmount",
                        ledgerTotalReservedAmount
                )
                .param(
                        "redisTotalSpentAmount",
                        redisTotalSpentAmount
                )
                .param(
                        "redisTotalReservedAmount",
                        redisTotalReservedAmount
                )
                .param("ledgerSpentAmount", ledgerSpentAmount)
                .param("ledgerReservedAmount", ledgerReservedAmount)
                .param("redisSpentAmount", redisSpentAmount)
                .param("redisReservedAmount", redisReservedAmount)
                .param("mismatchAmount", mismatchAmount)
                .param("status", status)
                .param("reconciledAt", reconciledAt)
                .update();
    }
}
