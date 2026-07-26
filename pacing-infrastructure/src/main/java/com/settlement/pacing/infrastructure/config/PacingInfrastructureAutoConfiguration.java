package com.settlement.pacing.infrastructure.config;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.HmacSecurityProperties;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.BudgetReservationGateway;
import com.settlement.pacing.api.gateway.BudgetStateQueryGateway;
import com.settlement.pacing.api.gateway.CampaignQueryGateway;
import com.settlement.pacing.api.gateway.CampaignManagementGateway;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.api.gateway.PacingObservationGateway;
import com.settlement.pacing.api.gateway.PeakPolicyGateway;
import com.settlement.pacing.api.security.ClientRateLimiter;
import com.settlement.pacing.api.security.NonceStore;
import com.settlement.pacing.infrastructure.audit.AuditLogJpaRepository;
import com.settlement.pacing.infrastructure.audit.AuditLogSanitizer;
import com.settlement.pacing.infrastructure.audit.PostgresAuditLogger;
import com.settlement.pacing.infrastructure.budget.BudgetReservationJpaRepository;
import com.settlement.pacing.infrastructure.budget.BudgetReservationMapper;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryQueryRepository;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetReservationAdapter;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateQueryAdapter;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.infrastructure.budget.ReservationPersistenceService;
import com.settlement.pacing.infrastructure.campaign.CampaignJpaRepository;
import com.settlement.pacing.infrastructure.campaign.CampaignMapper;
import com.settlement.pacing.infrastructure.campaign.CampaignManagementAdapter;
import com.settlement.pacing.infrastructure.campaign.CampaignQueryAdapter;
import com.settlement.pacing.infrastructure.campaign.RedisCampaignCache;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.common.RedisRecoveryLock;
import com.settlement.pacing.infrastructure.pacing.PacingStateSnapshotJpaRepository;
import com.settlement.pacing.infrastructure.pacing.PacingStateSnapshotStore;
import com.settlement.pacing.infrastructure.pacing.PeakPolicyJpaRepository;
import com.settlement.pacing.infrastructure.pacing.PostgresPeakPolicyAdapter;
import com.settlement.pacing.infrastructure.pacing.RedisPacingStateAdapter;
import com.settlement.pacing.infrastructure.pacing.RedisPacingObservationAdapter;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import com.settlement.pacing.infrastructure.security.RedisClientRateLimiter;
import com.settlement.pacing.infrastructure.security.RedisNonceStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.util.List;

@AutoConfiguration
@ConditionalOnClass(
        name = "com.settlement.pacing.api.gateway.CampaignQueryGateway"
)
@EnableConfigurationProperties({
        RedisInfrastructureProperties.class,
        RateLimitProperties.class
})
@EntityScan(basePackages =
        "com.settlement.pacing.infrastructure")
@EnableJpaRepositories(basePackages =
        "com.settlement.pacing.infrastructure")
@Import(RedisScriptConfiguration.class)
public class PacingInfrastructureAutoConfiguration {

    @Bean
    public PacingInfrastructureMetrics
    pacingInfrastructureMetrics(
            MeterRegistry meterRegistry
    ) {
        return new PacingInfrastructureMetrics(meterRegistry);
    }

    @Bean
    public RedisKeyFactory redisKeyFactory(
            RedisInfrastructureProperties properties
    ) {
        return new RedisKeyFactory(properties);
    }

    @Bean
    public CampaignMapper campaignMapper() {
        return new CampaignMapper();
    }

    @Bean
    public RedisCampaignCache redisCampaignCache(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties
    ) {
        return new RedisCampaignCache(
                redisTemplate,
                keyFactory,
                properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(CampaignQueryGateway.class)
    public CampaignQueryGateway campaignQueryGateway(
            CampaignJpaRepository repository,
            CampaignMapper mapper,
            RedisCampaignCache cache
    ) {
        return new CampaignQueryAdapter(
                repository,
                mapper,
                cache
        );
    }

    @Bean
    @ConditionalOnMissingBean(CampaignManagementGateway.class)
    public CampaignManagementGateway campaignManagementGateway(
            CampaignJpaRepository repository,
            RedisCampaignCache cache,
            RedisBudgetStateStore budgetStateStore
    ) {
        return new CampaignManagementAdapter(
                repository,
                cache,
                budgetStateStore
        );
    }

    @Bean
    public RedisRecoveryLock redisRecoveryLock(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties,
            @Qualifier("releaseLockScript")
            RedisScript<Long> releaseLockScript
    ) {
        return new RedisRecoveryLock(
                redisTemplate,
                keyFactory,
                properties,
                releaseLockScript
        );
    }

    @Bean
    public RedisBudgetStateStore redisBudgetStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("readBudgetStateScript")
            RedisScript<List> readBudgetStateScript,
            @Qualifier("initializeBudgetStateScript")
            RedisScript<Long> initializeBudgetStateScript,
            @Qualifier("updateBudgetLimitsScript")
            RedisScript<List> updateBudgetLimitsScript
    ) {
        return new RedisBudgetStateStore(
                redisTemplate,
                keyFactory,
                readBudgetStateScript,
                initializeBudgetStateScript,
                updateBudgetLimitsScript
        );
    }

    @Bean
    public BudgetStateRecoveryQueryRepository
    budgetStateRecoveryQueryRepository(
            JdbcClient jdbcClient
    ) {
        return new BudgetStateRecoveryQueryRepository(jdbcClient);
    }

    @Bean
    public BudgetStateRecoveryService budgetStateRecoveryService(
            BudgetStateRecoveryQueryRepository queryRepository,
            RedisBudgetStateStore budgetStateStore,
            RedisRecoveryLock recoveryLock,
            RedisInfrastructureProperties properties,
            PacingInfrastructureMetrics metrics
    ) {
        return new BudgetStateRecoveryService(
                queryRepository,
                budgetStateStore,
                recoveryLock,
                properties,
                metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean(BudgetStateQueryGateway.class)
    public BudgetStateQueryGateway budgetStateQueryGateway(
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService
    ) {
        return new RedisBudgetStateQueryAdapter(
                budgetStateStore,
                recoveryService
        );
    }

    @Bean
    public PacingStateSnapshotStore pacingStateSnapshotStore(
            PacingStateSnapshotJpaRepository repository,
            Clock clock
    ) {
        return new PacingStateSnapshotStore(repository, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PacingObservationGateway.class)
    public PacingObservationGateway pacingObservationGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("recordPacingDecisionScript")
            RedisScript<Long> recordDecisionScript,
            @Qualifier("recordPacingReservationScript")
            RedisScript<Long> recordReservationScript,
            @Qualifier("readPacingObservationScript")
            RedisScript<List> readObservationScript,
            PacingProperties properties,
            Clock clock
    ) {
        return new RedisPacingObservationAdapter(
                redisTemplate,
                keyFactory,
                recordDecisionScript,
                recordReservationScript,
                readObservationScript,
                properties,
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean(PeakPolicyGateway.class)
    public PeakPolicyGateway peakPolicyGateway(
            PeakPolicyJpaRepository repository,
            Clock clock
    ) {
        return new PostgresPeakPolicyAdapter(repository, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PacingStateGateway.class)
    public PacingStateGateway pacingStateGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("getOrInitializePacingStateScript")
            RedisScript<List> getOrInitializeScript,
            @Qualifier("compareAndSetPacingStateScript")
            RedisScript<List> compareAndSetScript,
            PacingStateSnapshotStore snapshotStore,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisPacingStateAdapter(
                redisTemplate,
                keyFactory,
                getOrInitializeScript,
                compareAndSetScript,
                snapshotStore,
                metrics
        );
    }

    @Bean
    public BudgetReservationMapper budgetReservationMapper() {
        return new BudgetReservationMapper();
    }

    @Bean
    public ReservationPersistenceService
    reservationPersistenceService(
            BudgetReservationJpaRepository repository,
            BudgetReservationMapper mapper,
            Clock clock
    ) {
        return new ReservationPersistenceService(
                repository,
                mapper,
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean(BudgetReservationGateway.class)
    public BudgetReservationGateway budgetReservationGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("reserveBudgetScript")
            RedisScript<List> reserveBudgetScript,
            @Qualifier("compensateReservationScript")
            RedisScript<Long> compensateReservationScript,
            BudgetStateQueryGateway budgetStateQueryGateway,
            ReservationPersistenceService persistenceService,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisBudgetReservationAdapter(
                redisTemplate,
                keyFactory,
                reserveBudgetScript,
                compensateReservationScript,
                budgetStateQueryGateway,
                persistenceService,
                metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean(NonceStore.class)
    public NonceStore nonceStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisNonceStore(
                redisTemplate,
                keyFactory,
                metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean(ClientRateLimiter.class)
    public ClientRateLimiter clientRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RateLimitProperties properties,
            @Qualifier("rateLimitTokenBucketScript")
            RedisScript<Long> tokenBucketScript,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisClientRateLimiter(
                redisTemplate,
                keyFactory,
                properties,
                tokenBucketScript,
                metrics
        );
    }

    @Bean
    public RateLimitConfigurationValidator
    rateLimitConfigurationValidator(
            HmacSecurityProperties hmacProperties,
            RateLimitProperties rateLimitProperties
    ) {
        return new RateLimitConfigurationValidator(
                hmacProperties,
                rateLimitProperties
        );
    }

    @Bean
    public AuditLogSanitizer auditLogSanitizer() {
        return new AuditLogSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogger.class)
    public AuditLogger auditLogger(
            AuditLogJpaRepository repository,
            AuditLogSanitizer sanitizer,
            Clock clock
    ) {
        return new PostgresAuditLogger(
                repository,
                sanitizer,
                clock
        );
    }
}
