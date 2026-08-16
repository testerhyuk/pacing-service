package com.settlement.pacing.infrastructure.common;

import com.settlement.pacing.infrastructure.config.RedisInfrastructureProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisClusterLuaIntegrationTest {
    private static final String CAMPAIGN_ID = "cluster-campaign-1";
    private static final String RESERVATION_ID =
            "cluster-reservation-1";
    private static final LocalDate BUDGET_DATE =
            LocalDate.of(2026, 8, 13);

    private static final RedisKeyFactory KEY_FACTORY =
            new RedisKeyFactory(properties());

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS_CLUSTER =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7.4.9-alpine")
            )
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(
                                    "redis/reserve_budget.lua"
                            ),
                            "/scripts/reserve_budget.lua"
                    )
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(
                                    "redis/claim_reservation_repairs.lua"
                            ),
                            "/scripts/claim_reservation_repairs.lua"
                    )
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(
                                    "redis/complete_reservation_repair_claim.lua"
                            ),
                            "/scripts/complete_reservation_repair_claim.lua"
                    )
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(
                                    "redis/release_reservation_repair_claim.lua"
                            ),
                            "/scripts/release_reservation_repair_claim.lua"
                    )
                    .withCommand(
                            "sh",
                            "-c",
                            clusterStartCommand()
                    )
                    .waitingFor(Wait.forLogMessage(
                            ".*REDIS_CLUSTER_READY.*\\n",
                            1
                    ))
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void 예산_예약과_복구_Lua는_Redis_Cluster에서_CROSSSLOT_없이_실행된다()
            throws Exception {
        String totalKey = KEY_FACTORY.totalBudget(CAMPAIGN_ID);
        String dailyKey = KEY_FACTORY.dailyBudget(
                CAMPAIGN_ID,
                BUDGET_DATE
        );
        String reservationKey = KEY_FACTORY.reservation(
                CAMPAIGN_ID,
                RESERVATION_ID
        );
        String expiryKey = KEY_FACTORY.reservationExpiry(CAMPAIGN_ID);
        String pendingKey =
                KEY_FACTORY.campaignReservationPersistencePending(
                        CAMPAIGN_ID
                );
        String processingKey =
                KEY_FACTORY.campaignReservationPersistenceProcessing(
                        CAMPAIGN_ID
                );
        String member = KEY_FACTORY.reservationPersistenceMember(
                CAMPAIGN_ID,
                RESERVATION_ID
        );

        redis(
                "HSET",
                totalKey,
                "totalBudget", "10000",
                "totalSpentAmount", "0",
                "totalReservedAmount", "0",
                "version", "0"
        );
        redis(
                "HSET",
                dailyKey,
                "dailyBudgetLimit", "10000",
                "dailySpentAmount", "0",
                "dailyReservedAmount", "0",
                "version", "0"
        );

        Container.ExecResult reserved = eval(
                "/scripts/reserve_budget.lua",
                List.of(
                        totalKey,
                        dailyKey,
                        reservationKey,
                        expiryKey,
                        pendingKey
                ),
                RESERVATION_ID,
                CAMPAIGN_ID,
                BUDGET_DATE.toString(),
                "500",
                "1000",
                "10000",
                member,
                "1000"
        );

        assertSuccessful(reserved);
        assertThat(reserved.getStdout()).contains("CREATED");

        Container.ExecResult claimed = eval(
                "/scripts/claim_reservation_repairs.lua",
                List.of(pendingKey, processingKey),
                "2000",
                "2000",
                "12000",
                "10",
                "worker-token-1"
        );

        assertSuccessful(claimed);
        assertThat(claimed.getStdout()).contains(member);

        Container.ExecResult released = eval(
                "/scripts/release_reservation_repair_claim.lua",
                List.of(pendingKey, processingKey),
                "worker-token-1|" + member,
                member,
                "1500"
        );

        assertSuccessful(released);
        assertThat(released.getStdout().trim()).isEqualTo("1");

        Container.ExecResult reclaimed = eval(
                "/scripts/claim_reservation_repairs.lua",
                List.of(pendingKey, processingKey),
                "2000",
                "2000",
                "12000",
                "10",
                "worker-token-2"
        );

        assertSuccessful(reclaimed);
        assertThat(reclaimed.getStdout()).contains(member);

        Container.ExecResult completed = eval(
                "/scripts/complete_reservation_repair_claim.lua",
                List.of(processingKey),
                "worker-token-2|" + member
        );

        assertSuccessful(completed);
        assertThat(completed.getStdout().trim()).isEqualTo("1");
        assertThat(redis("ZCARD", pendingKey).getStdout().trim())
                .isEqualTo("0");
        assertThat(redis("ZCARD", processingKey).getStdout().trim())
                .isEqualTo("0");
    }

    @Test
    void 서로_다른_캠페인은_Redis_Cluster의_서로_다른_slot을_사용한다()
            throws Exception {
        String firstKey = KEY_FACTORY.totalBudget("cluster-campaign-1");
        String secondKey = KEY_FACTORY.totalBudget("cluster-campaign-2");

        String firstSlot = redis("CLUSTER", "KEYSLOT", firstKey)
                .getStdout()
                .trim();
        String secondSlot = redis("CLUSTER", "KEYSLOT", secondKey)
                .getStdout()
                .trim();

        assertThat(firstSlot).isNotEqualTo(secondSlot);
    }

    private Container.ExecResult eval(
            String script,
            List<String> keys,
            String... arguments
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("redis-cli");
        command.add("-c");
        command.add("-p");
        command.add("7000");
        command.add("--raw");
        command.add("--eval");
        command.add(script);
        command.addAll(keys);
        command.add(",");
        command.addAll(List.of(arguments));

        return REDIS_CLUSTER.execInContainer(
                command.toArray(String[]::new)
        );
    }

    private Container.ExecResult redis(String... arguments)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add("redis-cli");
        command.add("-c");
        command.add("-p");
        command.add("7000");
        command.add("--raw");
        command.addAll(List.of(arguments));

        Container.ExecResult result = REDIS_CLUSTER.execInContainer(
                command.toArray(String[]::new)
        );
        assertSuccessful(result);
        return result;
    }

    private void assertSuccessful(Container.ExecResult result) {
        assertThat(result.getExitCode())
                .withFailMessage(
                        "stdout=%s stderr=%s",
                        result.getStdout(),
                        result.getStderr()
                )
                .isZero();
        assertThat(result.getStdout()).doesNotContain("CROSSSLOT");
        assertThat(result.getStderr()).doesNotContain("CROSSSLOT");
    }

    private static String clusterStartCommand() {
        return """
                set -eu
                for port in 7000 7001 7002; do
                  mkdir -p /cluster/$port
                  redis-server \
                    --port $port \
                    --cluster-enabled yes \
                    --cluster-config-file nodes.conf \
                    --cluster-node-timeout 5000 \
                    --appendonly no \
                    --save '' \
                    --protected-mode no \
                    --daemonize yes \
                    --dir /cluster/$port \
                    --logfile /cluster/$port/redis.log
                done
                for port in 7000 7001 7002; do
                  until redis-cli -p $port ping >/dev/null 2>&1; do
                    sleep 1
                  done
                done
                redis-cli --cluster create \
                  127.0.0.1:7000 \
                  127.0.0.1:7001 \
                  127.0.0.1:7002 \
                  --cluster-replicas 0 \
                  --cluster-yes
                until redis-cli -p 7000 cluster info \
                    | grep -q 'cluster_state:ok'; do
                  sleep 1
                done
                echo REDIS_CLUSTER_READY
                tail -f /dev/null
                """;
    }

    private static RedisInfrastructureProperties properties() {
        return new RedisInfrastructureProperties(
                "cluster-it",
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                Duration.ofSeconds(3),
                Duration.ofSeconds(2),
                Duration.ofMillis(20)
        );
    }
}
