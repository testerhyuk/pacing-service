package com.settlement.pacing.worker.config;

import com.settlement.pacing.core.billing.BillingEventProcessor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableKafkaRetryTopic
public class PacingWorkerConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public BillingEventProcessor billingEventProcessor() {
        return new BillingEventProcessor();
    }

    @Bean
    public NewTopic billingEventTopic(
            PacingWorkerProperties properties
    ) {
        PacingWorkerProperties.Kafka kafka = properties.kafka();

        return TopicBuilder.name(kafka.billingTopic())
                .partitions(kafka.partitions())
                .replicas(kafka.replicationFactor())
                .build();
    }
}
