package com.settlement.pacing.infrastructure.budget;

import com.settlement.pacing.core.budget.BudgetState;
import com.settlement.pacing.core.budget.Money;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.List;

public class RedisBudgetStateStore {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<List> readBudgetStateScript;
    private final RedisScript<Long> initializeBudgetStateScript;

    public RedisBudgetStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<List> readBudgetStateScript,
            RedisScript<Long> initializeBudgetStateScript
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.readBudgetStateScript = readBudgetStateScript;
        this.initializeBudgetStateScript =
                initializeBudgetStateScript;
    }

    public ReadResult read(
            String campaignId,
            LocalDate budgetDate
    ) {
        List<?> result = redisTemplate.execute(
                readBudgetStateScript,
                List.of(
                        keyFactory.totalBudget(campaignId),
                        keyFactory.dailyBudget(
                                campaignId,
                                budgetDate
                        )
                )
        );

        if (result == null || result.isEmpty()) {
            throw new DataIntegrityViolationException(
                    "Redis 예산 상태 조회 결과가 비어있습니다"
            );
        }

        String statusValue = value(result, 0);
        ReadStatus status;

        try {
            status = ReadStatus.valueOf(statusValue);
        } catch (IllegalArgumentException exception) {
            throw corrupted(
                    "알 수 없는 Redis 예산 상태입니다: "
                            + statusValue,
                    exception
            );
        }

        if (status == ReadStatus.CORRUPTED) {
            throw corrupted(
                    "Redis 예산 Hash의 필수 필드가 누락됐습니다",
                    null
            );
        }

        if (status != ReadStatus.FOUND) {
            return new ReadResult(status, null, -1, -1);
        }

        if (result.size() != 9) {
            throw corrupted(
                    "Redis 예산 상태 필드 수가 올바르지 않습니다",
                    null
            );
        }

        try {
            BudgetState budgetState = new BudgetState(
                    campaignId,
                    budgetDate,
                    new Money(parseAmount(result, 1)),
                    new Money(parseAmount(result, 2)),
                    new Money(parseAmount(result, 3)),
                    new Money(parseAmount(result, 4)),
                    new Money(parseAmount(result, 5)),
                    new Money(parseAmount(result, 6))
            );

            return new ReadResult(
                    status,
                    budgetState,
                    parseVersion(result, 7),
                    parseVersion(result, 8)
            );
        } catch (IllegalArgumentException
                 | ArithmeticException exception) {
            throw corrupted(
                    "Redis 예산 상태 값이 올바르지 않습니다",
                    exception
            );
        }
    }

    public void initializeIfAbsent(BudgetState budgetState) {
        if (budgetState == null) {
            throw new IllegalArgumentException(
                    "BudgetState는 null일 수 없습니다"
            );
        }

        redisTemplate.execute(
                initializeBudgetStateScript,
                List.of(
                        keyFactory.totalBudget(
                                budgetState.campaignId()
                        ),
                        keyFactory.dailyBudget(
                                budgetState.campaignId(),
                                budgetState.budgetDate()
                        )
                ),
                Long.toString(
                        budgetState.totalBudget().amount()
                ),
                Long.toString(
                        budgetState.totalSpentAmount().amount()
                ),
                Long.toString(
                        budgetState.totalReservedAmount().amount()
                ),
                Long.toString(
                        budgetState.dailyBudgetLimit().amount()
                ),
                Long.toString(
                        budgetState.dailySpentAmount().amount()
                ),
                Long.toString(
                        budgetState.dailyReservedAmount().amount()
                )
        );
    }

    private long parseAmount(List<?> result, int index) {
        long parsed = Long.parseLong(value(result, index));

        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "예산 금액은 음수일 수 없습니다"
            );
        }

        return parsed;
    }

    private long parseVersion(List<?> result, int index) {
        long parsed = Long.parseLong(value(result, index));

        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "예산 version은 음수일 수 없습니다"
            );
        }

        return parsed;
    }

    private String value(List<?> result, int index) {
        Object value = result.get(index);

        if (value == null) {
            throw corrupted(
                    "Redis 예산 상태 값이 null입니다",
                    null
            );
        }

        return value.toString();
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

    public enum ReadStatus {
        FOUND,
        MISSING_TOTAL,
        MISSING_DAILY,
        MISSING_BOTH,
        CORRUPTED
    }

    public record ReadResult(
            ReadStatus status,
            BudgetState budgetState,
            long totalVersion,
            long dailyVersion
    ) {
        public boolean found() {
            return status == ReadStatus.FOUND;
        }
    }
}
