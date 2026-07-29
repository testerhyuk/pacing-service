package com.settlement.pacing.infrastructure.config;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.HmacSecurityProperties;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.*;
import com.settlement.pacing.api.security.RequestAdmissionGateway;
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
import com.settlement.pacing.infrastructure.campaign.*;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.common.RedisRecoveryLock;
import com.settlement.pacing.infrastructure.decision.RedisDecisionContextQueryAdapter;
import com.settlement.pacing.infrastructure.pacing.*;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import com.settlement.pacing.infrastructure.security.RedisRequestAdmissionAdapter;
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
            RedisCampaignCache cache,
            RedisCampaignCacheLoadLock cacheLoadLock,
            RedisInfrastructureProperties properties
    ) {
        return new CampaignQueryAdapter(
                repository,
                mapper,
                cache,
                cacheLoadLock,
                properties
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
        return new PacingStateSnapshotStore(
                repository,
                clock
        );
    }

    @Bean
    public PacingStateSnapshotPersistenceCoordinator
    pacingStateSnapshotPersistenceCoordinator(
            PacingStateSnapshotStore snapshotStore
    ) {
        return new PacingStateSnapshotPersistenceCoordinator(
                snapshotStore
        );
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
            PacingStateSnapshotPersistenceCoordinator
                    snapshotPersistenceCoordinator,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisPacingStateAdapter(
                redisTemplate,
                keyFactory,
                getOrInitializeScript,
                compareAndSetScript,
                snapshotStore,
                snapshotPersistenceCoordinator,
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
            PacingInfrastructureMetrics metrics,
            Clock clock
    ) {
        return new RedisBudgetReservationAdapter(
                redisTemplate,
                keyFactory,
                reserveBudgetScript,
                compensateReservationScript,
                budgetStateQueryGateway,
                persistenceService,
                metrics,
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean(RequestAdmissionGateway.class)
    public RequestAdmissionGateway requestAdmissionGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RateLimitProperties properties,
            @Qualifier("admitRequestScript")
            RedisScript<String> admitRequestScript,
            PacingInfrastructureMetrics metrics
    ) {
        return new RedisRequestAdmissionAdapter(
                redisTemplate,
                keyFactory,
                properties,
                admitRequestScript,
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

    @Bean
    public RedisCampaignCacheLoadLock redisCampaignCacheLoadLock(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            RedisInfrastructureProperties properties,
            @Qualifier("releaseLockScript")
            RedisScript<Long> releaseLockScript
    ) {
        return new RedisCampaignCacheLoadLock(
                redisTemplate,
                keyFactory,
                properties,
                releaseLockScript
        );
    }

    @Bean
    @ConditionalOnMissingBean(DecisionContextQueryGateway.class)
    public DecisionContextQueryGateway decisionContextQueryGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("readDecisionContextScript")
            RedisScript<List> readDecisionContextScript,
            RedisCampaignCache campaignCache,
            PacingStateSnapshotPersistenceCoordinator
                    snapshotPersistenceCoordinator
    ) {
        return new RedisDecisionContextQueryAdapter(
                redisTemplate,
                keyFactory,
                readDecisionContextScript,
                campaignCache,
                snapshotPersistenceCoordinator
        );
    }
}
