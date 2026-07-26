package com.settlement.pacing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class RedisScriptConfiguration {

    @Bean
    public RedisScript<List> readBudgetStateScript() {
        return listScript("redis/read_budget_state.lua");
    }

    @Bean
    public RedisScript<Long> initializeBudgetStateScript() {
        return longScript("redis/initialize_budget_state.lua");
    }

    @Bean
    public RedisScript<List> updateBudgetLimitsScript() {
        return listScript("redis/update_budget_limits.lua");
    }

    @Bean
    public RedisScript<List> reserveBudgetScript() {
        return listScript("redis/reserve_budget.lua");
    }

    @Bean
    public RedisScript<Long> compensateReservationScript() {
        return longScript("redis/compensate_reservation.lua");
    }

    @Bean
    public RedisScript<List> initializeWorkerReservationScript() {
        return listScript(
                "redis/initialize_worker_reservation.lua"
        );
    }

    @Bean
    public RedisScript<List> applyBillingEventScript() {
        return listScript("redis/apply_billing_event.lua");
    }

    @Bean
    public RedisScript<List> expireReservationScript() {
        return listScript("redis/expire_reservation.lua");
    }

    @Bean
    public RedisScript<List> getOrInitializePacingStateScript() {
        return listScript(
                "redis/get_or_initialize_pacing_state.lua"
        );
    }

    @Bean
    public RedisScript<List> compareAndSetPacingStateScript() {
        return listScript(
                "redis/compare_and_set_pacing_state.lua"
        );
    }

    @Bean
    public RedisScript<Long> recordPacingDecisionScript() {
        return longScript("redis/record_pacing_decision.lua");
    }

    @Bean
    public RedisScript<Long> recordPacingReservationScript() {
        return longScript("redis/record_pacing_reservation.lua");
    }

    @Bean
    public RedisScript<List> readPacingObservationScript() {
        return listScript("redis/read_pacing_observation.lua");
    }

    @Bean
    public RedisScript<Long> rateLimitTokenBucketScript() {
        return longScript("redis/rate_limit_token_bucket.lua");
    }

    @Bean
    public RedisScript<Long> releaseLockScript() {
        return longScript("redis/release_lock.lua");
    }

    private RedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script =
                new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private RedisScript<Long> longScript(String path) {
        DefaultRedisScript<Long> script =
                new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
