package com.settlement.pacing.worker.billing.kafka;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.worker.billing.application.BillingEventHandler;
import com.settlement.pacing.worker.billing.message.BillingEventMessage;
import com.settlement.pacing.worker.error.NonRetryableBillingEventException;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import static org.springframework.kafka.support.KafkaHeaders.RECEIVED_KEY;

@Component
public class BillingEventListener {
    private final BillingEventHandler handler;

    public BillingEventListener(BillingEventHandler handler) {
        this.handler = handler;
    }

    @RetryableTopic(
            attempts =
                    "${pacing.worker.kafka.retry-attempts}",
            backOff = @BackOff(
                    delayString =
                            "${pacing.worker.kafka.initial-backoff-millis}",
                    multiplierString =
                            "${pacing.worker.kafka.backoff-multiplier}",
                    maxDelayString =
                            "${pacing.worker.kafka.max-backoff-millis}"
            ),
            include = RetryableBillingEventException.class,
            autoCreateTopics = "true",
            numPartitions =
                    "${pacing.worker.kafka.partitions}",
            replicationFactor =
                    "${pacing.worker.kafka.replication-factor}",
            topicSuffixingStrategy =
                    TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    @KafkaListener(
            topics = "${pacing.worker.kafka.billing-topic}",
            groupId = "${pacing.worker.kafka.consumer-group}",
            concurrency = "${pacing.worker.kafka.concurrency}"
    )
    public void consume(
            BillingEventMessage message,
            @Header(name = RECEIVED_KEY, required = false)
            String messageKey
    ) {
        if (message == null) {
            throw new NonRetryableBillingEventException(
                    "Kafka 과금 이벤트 본문은 null일 수 없습니다"
            );
        }

        BillingEvent event;
        try {
            event = message.toDomain();
        } catch (IllegalArgumentException exception) {
            throw new NonRetryableBillingEventException(
                    "Kafka 과금 이벤트 값이 올바르지 않습니다",
                    exception
            );
        }

        if (!event.reservationId().equals(messageKey)) {
            throw new NonRetryableBillingEventException(
                    "Kafka message key는 reservationId와 일치해야 합니다"
            );
        }

        try {
            handler.handle(event);
        } catch (DataAccessException exception) {
            throw new RetryableBillingEventException(
                    "과금 처리 저장소에 일시적으로 접근할 수 없습니다",
                    exception
            );
        }
    }

    @DltHandler
    public void deadLetter(
            BillingEventMessage message
    ) {
        BillingEventHandler.BillingEventMessageView view =
                new BillingEventHandler.BillingEventMessageView(
                        message == null ? null : message.eventId(),
                        message == null || message.eventType() == null
                                ? null
                                : message.eventType().name()
                );

        handler.markDeadLetter(
                view,
                "Kafka 재시도 횟수를 초과했습니다"
        );
    }
}
