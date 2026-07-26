package com.settlement.pacing.api.admin.application;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.PeakPolicyGateway;
import com.settlement.pacing.core.pacing.PeakPolicy;
import com.settlement.pacing.core.pacing.PeakTimeWindow;
import com.settlement.pacing.core.pacing.TrafficWeight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicPeakPolicyServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T00:00:00Z");

    private PeakPolicyGateway gateway;
    private AuditLogger auditLogger;
    private DynamicPeakPolicyService service;

    @BeforeEach
    void setUp() {
        gateway = mock(PeakPolicyGateway.class);
        auditLogger = mock(AuditLogger.class);
        PacingProperties properties =
                mock(PacingProperties.class);
        when(properties.peak()).thenReturn(
                new PacingProperties.Peak(
                        LocalTime.of(18, 0),
                        LocalTime.of(23, 0),
                        ZoneId.of("Asia/Seoul"),
                        0.5,
                        1.5
                )
        );

        service = new DynamicPeakPolicyService(
                gateway,
                properties,
                auditLogger,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 저장된_정책이_없으면_설정값을_초기_정책으로_저장한다() {
        when(gateway.find()).thenReturn(Optional.empty());

        service.initialize();

        assertThat(service.current().timeWindow().startTime())
                .isEqualTo(LocalTime.of(18, 0));
        verify(gateway).save(service.current());
    }

    @Test
    void 운영에서_변경한_정책을_즉시_현재_정책으로_사용한다() {
        when(gateway.find()).thenReturn(Optional.of(policy(
                LocalTime.of(18, 0),
                LocalTime.of(23, 0)
        )));
        service.initialize();

        PeakPolicy updated = service.update(
                new UpdatePeakPolicyCommand(
                        LocalTime.of(17, 0),
                        LocalTime.of(22, 0),
                        ZoneId.of("Asia/Seoul"),
                        0.4,
                        1.8
                ),
                "operation-server",
                "nonce-1"
        );

        assertThat(service.current()).isEqualTo(updated);
        verify(gateway).save(updated);
        verify(auditLogger).log(any(
                AuditLogger.AuditEvent.class
        ));
    }

    private PeakPolicy policy(
            LocalTime startTime,
            LocalTime endTime
    ) {
        return new PeakPolicy(
                new PeakTimeWindow(
                        startTime,
                        endTime,
                        ZoneId.of("Asia/Seoul")
                ),
                new TrafficWeight(0.5, 1.5)
        );
    }
}
