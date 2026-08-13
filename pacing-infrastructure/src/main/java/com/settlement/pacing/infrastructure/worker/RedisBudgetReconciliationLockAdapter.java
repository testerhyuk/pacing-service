package com.settlement.pacing.infrastructure.worker;

import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationLockGateway;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedisBudgetReconciliationLockAdapter
        implements BudgetReconciliationLockGateway {
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final RedisScript<Long> releaseLockScript;

    public RedisBudgetReconciliationLockAdapter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisScript<Long> releaseLockScript
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.releaseLockScript = releaseLockScript;
    }

    @Override
    public Optional<LockHandle> tryAcquire(
            LocalDate budgetDate,
            Duration ttl
    ) {
        if (budgetDate == null
                || ttl == null
                || ttl.isZero()
                || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "예산 대사 Lock 기준일과 TTL이 올바르지 않습니다"
            );
        }

        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(
                        keyFactory.budgetReconciliationLock(
                                budgetDate
                        ),
                        token,
                        ttl
                );

        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        return Optional.of(new LockHandle(budgetDate, token));
    }

    @Override
    public void release(LockHandle lockHandle) {
        if (lockHandle == null) {
            throw new IllegalArgumentException(
                    "예산 대사 Lock 정보는 null일 수 없습니다"
            );
        }

        redisTemplate.execute(
                releaseLockScript,
                List.of(keyFactory.budgetReconciliationLock(
                        lockHandle.budgetDate()
                )),
                lockHandle.token()
        );
    }
}
