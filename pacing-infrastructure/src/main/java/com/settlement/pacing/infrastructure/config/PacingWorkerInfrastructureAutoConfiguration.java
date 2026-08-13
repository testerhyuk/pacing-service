package com.settlement.pacing.infrastructure.config;

import com.settlement.pacing.core.billing.BillingEventProcessor;
import com.settlement.pacing.infrastructure.budget.BudgetReservationJpaRepository;
import com.settlement.pacing.infrastructure.budget.BudgetReservationEntity;
import com.settlement.pacing.infrastructure.budget.BudgetReservationMapper;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryQueryRepository;
import com.settlement.pacing.infrastructure.budget.BudgetStateRecoveryService;
import com.settlement.pacing.infrastructure.budget.RedisBudgetStateStore;
import com.settlement.pacing.infrastructure.budget.ReservationPersistenceService;
import com.settlement.pacing.infrastructure.common.RedisKeyFactory;
import com.settlement.pacing.infrastructure.common.RedisRecoveryLock;
import com.settlement.pacing.infrastructure.monitoring.PacingInfrastructureMetrics;
import com.settlement.pacing.infrastructure.worker.BillingEventJpaRepository;
import com.settlement.pacing.infrastructure.worker.BillingEventEntity;
import com.settlement.pacing.infrastructure.worker.BillingEventPersistenceService;
import com.settlement.pacing.infrastructure.worker.BudgetReconciliationAdapter;
import com.settlement.pacing.infrastructure.worker.CampaignRepairScanRepository;
import com.settlement.pacing.infrastructure.worker.RedisBudgetReconciliationLockAdapter;
import com.settlement.pacing.infrastructure.worker.BudgetReconciliationRepository;
import com.settlement.pacing.infrastructure.worker.ExpirationClaimRepository;
import com.settlement.pacing.infrastructure.worker.RedisBillingEventProcessingAdapter;
import com.settlement.pacing.infrastructure.worker.RedisExpiredReservationAdapter;
import com.settlement.pacing.infrastructure.worker.RedisReservationRepairAdapter;
import com.settlement.pacing.infrastructure.worker.RedisWorkerStateStore;
import com.settlement.pacing.worker.billing.application.BillingEventProcessingGateway;
import com.settlement.pacing.worker.config.PacingWorkerProperties;
import com.settlement.pacing.worker.expiration.application.ExpiredReservationGateway;
import com.settlement.pacing.worker.reconciliation.application.ReservationRepairGateway;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationLockGateway;
import com.settlement.pacing.worker.reconciliation.application.BudgetReconciliationGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
        name = "com.settlement.pacing.worker.billing.application.BillingEventProcessingGateway"
)
@ConditionalOnProperty(
        prefix = "pacing.worker",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties({
        RedisInfrastructureProperties.class,
        PacingWorkerProperties.class
})
@EntityScan(basePackageClasses = {
        BudgetReservationEntity.class,
        BillingEventEntity.class
})
@EnableJpaRepositories(basePackageClasses = {
        BudgetReservationJpaRepository.class,
        BillingEventJpaRepository.class
})
@Import(RedisScriptConfiguration.class)
public class PacingWorkerInfrastructureAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PacingInfrastructureMetrics
    pacingInfrastructureMetrics(
            MeterRegistry meterRegistry
    ) {
        return new PacingInfrastructureMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisKeyFactory redisKeyFactory(
            RedisInfrastructureProperties properties
    ) {
        return new RedisKeyFactory(properties);
    }

    @Bean
    @ConditionalOnMissingBean
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
    @ConditionalOnMissingBean
    public RedisBudgetStateStore redisBudgetStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("readBudgetStateScript")
            RedisScript<List> readBudgetStateScript,
            @Qualifier("initializeBudgetStateScript")
            RedisScript<Long> initializeBudgetStateScript,
            @Qualifier("updateBudgetLimitsScript")
            RedisScript<List> updateBudgetLimitsScript,
            @Qualifier("repairBudgetStateScript")
            RedisScript<List> repairBudgetStateScript
    ) {
        return new RedisBudgetStateStore(
                redisTemplate,
                keyFactory,
                readBudgetStateScript,
                initializeBudgetStateScript,
                updateBudgetLimitsScript,
                repairBudgetStateScript
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public BudgetStateRecoveryQueryRepository
    budgetStateRecoveryQueryRepository(
            JdbcClient jdbcClient
    ) {
        return new BudgetStateRecoveryQueryRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
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
    public BillingEventPersistenceService
    billingEventPersistenceService(
            BillingEventJpaRepository eventRepository,
            BudgetReservationJpaRepository reservationRepository,
            Clock clock
    ) {
        return new BillingEventPersistenceService(
                eventRepository,
                reservationRepository,
                clock
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
    public RedisWorkerStateStore redisWorkerStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("initializeWorkerReservationScript")
            RedisScript<List> initializeReservationScript,
            @Qualifier("applyBillingEventScript")
            RedisScript<List> applyBillingEventScript,
            @Qualifier("expireReservationScript")
            RedisScript<List> expireReservationScript,
            PacingWorkerProperties properties
    ) {
        return new RedisWorkerStateStore(
                redisTemplate,
                keyFactory,
                initializeReservationScript,
                applyBillingEventScript,
                expireReservationScript,
                properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(BillingEventProcessingGateway.class)
    public BillingEventProcessingGateway
    billingEventProcessingGateway(
            BillingEventProcessor processor,
            BillingEventPersistenceService persistenceService,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            RedisWorkerStateStore workerStateStore
    ) {
        return new RedisBillingEventProcessingAdapter(
                processor,
                persistenceService,
                budgetStateStore,
                recoveryService,
                workerStateStore
        );
    }

    @Bean
    @ConditionalOnMissingBean(ExpiredReservationGateway.class)
    public ExpiredReservationGateway expiredReservationGateway(
            BillingEventPersistenceService persistenceService,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            RedisWorkerStateStore workerStateStore,
            ExpirationClaimRepository claimRepository,
            PacingWorkerProperties properties
    ) {
        return new RedisExpiredReservationAdapter(
                persistenceService,
                budgetStateStore,
                recoveryService,
                workerStateStore,
                claimRepository,
                properties.expiration().claimTtl()
        );
    }

    @Bean
    public ExpirationClaimRepository expirationClaimRepository(
            JdbcClient jdbcClient
    ) {
        return new ExpirationClaimRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean(ReservationRepairGateway.class)
    public ReservationRepairGateway reservationRepairGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ReservationPersistenceService persistenceService,
            CampaignRepairScanRepository campaignScanRepository,
            @Qualifier("compensateReservationScript")
            RedisScript<Long> compensateReservationScript,
            @Qualifier("claimReservationRepairsScript")
            RedisScript<List> claimReservationRepairsScript,
            @Qualifier("releaseReservationRepairClaimScript")
            RedisScript<Long> releaseReservationRepairClaimScript,
            @Qualifier("completeReservationRepairClaimScript")
            RedisScript<Long> completeReservationRepairClaimScript,
            Clock clock,
            PacingWorkerProperties properties
    ) {
        return new RedisReservationRepairAdapter(
                redisTemplate,
                keyFactory,
                persistenceService,
                campaignScanRepository,
                compensateReservationScript,
                claimReservationRepairsScript,
                releaseReservationRepairClaimScript,
                completeReservationRepairClaimScript,
                clock,
                properties.reservationRepair().claimTtl(),
                properties.reservationRepair()
                        .campaignScanBatchSize()
        );
    }

    @Bean
    public CampaignRepairScanRepository
    campaignRepairScanRepository(JdbcClient jdbcClient) {
        return new CampaignRepairScanRepository(jdbcClient);
    }

    @Bean
    public BudgetReconciliationRepository
    budgetReconciliationRepository(JdbcClient jdbcClient) {
        return new BudgetReconciliationRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean(BudgetReconciliationGateway.class)
    public BudgetReconciliationGateway
    budgetReconciliationGateway(
            BudgetStateRecoveryQueryRepository queryRepository,
            RedisBudgetStateStore budgetStateStore,
            BudgetStateRecoveryService recoveryService,
            BudgetReconciliationRepository repository,
            Clock clock,
            PacingWorkerProperties properties
    ) {
        return new BudgetReconciliationAdapter(
                queryRepository,
                budgetStateStore,
                recoveryService,
                repository,
                clock,
                properties.reconciliation().maxRepairAttempts()
        );
    }

    @Bean
    @ConditionalOnMissingBean(BudgetReconciliationLockGateway.class)
    public BudgetReconciliationLockGateway
    budgetReconciliationLockGateway(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("releaseLockScript")
            RedisScript<Long> releaseLockScript
    ) {
        return new RedisBudgetReconciliationLockAdapter(
                redisTemplate,
                keyFactory,
                releaseLockScript
        );
    }
}
