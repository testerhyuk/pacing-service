package com.settlement.pacing.worker.billing.kafka;

import com.settlement.pacing.core.billing.BillingEvent;
import com.settlement.pacing.core.billing.BillingEventType;
import com.settlement.pacing.worker.billing.application.BillingEventHandler;
import com.settlement.pacing.worker.billing.message.BillingEventMessage;
import com.settlement.pacing.worker.error.NonRetryableBillingEventException;
import com.settlement.pacing.worker.error.RetryableBillingEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BillingEventListenerTest {
    private BillingEventHandler handler;
    private BillingEventListener listener;

    @BeforeEach
    void setUp() {
        handler = mock(BillingEventHandler.class);
        listener = new BillingEventListener(handler);
    }

    @Test
    void reservationId와_같은_Key의_이벤트를_처리한다() {
        BillingEventMessage message = message();

        listener.consume(message, "reservation-1");

        verify(handler).handle(argThat(
                event -> sameEvent(event, message)
        ));
    }

    @Test
    void Kafka_Key가_reservationId와_다르면_재시도하지_않는_예외를_던진다() {
        assertThatThrownBy(() ->
                listener.consume(message(), "reservation-2")
        )
                .isInstanceOf(
                        NonRetryableBillingEventException.class
                );

        verifyNoInteractions(handler);
    }

    @Test
    void 잘못된_이벤트_본문은_재시도하지_않는_예외를_던진다() {
        BillingEventMessage invalid = new BillingEventMessage(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                0L,
                1L,
                Instant.parse("2026-07-26T01:00:00Z")
        );

        assertThatThrownBy(() ->
                listener.consume(invalid, "reservation-1")
        )
                .isInstanceOf(
                        NonRetryableBillingEventException.class
                );

        verifyNoInteractions(handler);
    }

    @Test
    void DLT_이벤트의_eventId와_사유를_기록한다() {
        BillingEventMessage message = message();

        listener.deadLetter(message);

        verify(handler).markDeadLetter(
                new BillingEventHandler.BillingEventMessageView(
                        "event-1",
                        "CHARGED"
                ),
                "Kafka 재시도 횟수를 초과했습니다"
        );
    }

    @Test
    void 저장소_일시_장애는_Kafka_재시도_예외로_변환한다() {
        BillingEventMessage message = message();
        doThrow(new DataAccessResourceFailureException(
                "temporary"
        )).when(handler).handle(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() ->
                listener.consume(message, "reservation-1")
        ).isInstanceOf(RetryableBillingEventException.class);
    }

    private BillingEventMessage message() {
        return new BillingEventMessage(
                "event-1",
                "reservation-1",
                BillingEventType.CHARGED,
                900L,
                1L,
                Instant.parse("2026-07-26T01:00:00Z")
        );
    }

    private boolean sameEvent(
            BillingEvent event,
            BillingEventMessage message
    ) {
        return event.eventId().equals(message.eventId())
                && event.reservationId().equals(
                        message.reservationId()
                )
                && event.eventType() == message.eventType()
                && event.targetAppliedAmount().amount()
                == message.targetAppliedAmount()
                && event.sequence() == message.sequence()
                && event.occurredAt().equals(
                        message.occurredAt()
                );
    }
}
