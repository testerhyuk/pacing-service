package com.settlement.pacing.api.admin.application;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.PacingProperties;
import com.settlement.pacing.api.gateway.CampaignManagementGateway;
import com.settlement.pacing.api.gateway.CampaignManagementGateway.CampaignSettings;
import com.settlement.pacing.api.gateway.PacingStateGateway;
import com.settlement.pacing.core.campaign.CampaignStatus;
import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CampaignAdminServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T01:00:00Z");
    private CampaignManagementGateway gateway;
    private PacingStateGateway pacingStateGateway;
    private AuditLogger auditLogger;
    private CampaignAdminService service;

    @BeforeEach
    void setUp() {
        gateway = mock(CampaignManagementGateway.class);
        pacingStateGateway = mock(PacingStateGateway.class);
        auditLogger = mock(AuditLogger.class);
        PacingProperties properties =
                mock(PacingProperties.class);
        when(properties.businessZoneId())
                .thenReturn(ZoneId.of("Asia/Seoul"));

        service = new CampaignAdminService(
                gateway,
                pacingStateGateway,
                properties,
                auditLogger,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 전략을_변경하면_기존_페이싱_상태를_초기화한다() {
        CampaignSettings before = settings(PacingStrategy.EVEN);
        CampaignSettings after =
                settings(PacingStrategy.PEAK_WEIGHTED);
        when(gateway.findById("campaign-1"))
                .thenReturn(Optional.of(before));
        when(gateway.save(
                any(CampaignSettings.class),
                eq(LocalDate.of(2026, 7, 26))
        )).thenReturn(after);

        CampaignSettings result = service.upsert(
                command(PacingStrategy.PEAK_WEIGHTED),
                "operation-server",
                "nonce-1"
        );

        assertThat(result).isEqualTo(after);
        verify(pacingStateGateway).delete("campaign-1");
        verify(auditLogger, org.mockito.Mockito.atLeastOnce())
                .log(any(AuditLogger.AuditEvent.class));
    }

    @Test
    void 전략이_같으면_페이싱_상태를_삭제하지_않는다() {
        CampaignSettings settings = settings(PacingStrategy.EVEN);
        when(gateway.findById("campaign-1"))
                .thenReturn(Optional.of(settings));
        when(gateway.save(any(), any())).thenReturn(settings);

        service.upsert(
                command(PacingStrategy.EVEN),
                "operation-server",
                "nonce-1"
        );

        verify(pacingStateGateway, never()).delete(any());
    }

    private UpsertCampaignCommand command(
            PacingStrategy strategy
    ) {
        return new UpsertCampaignCommand(
                "campaign-1",
                CampaignStatus.ACTIVE,
                NOW.minusSeconds(3_600),
                NOW.plusSeconds(86_400),
                strategy,
                1_000_000L,
                100_000L
        );
    }

    private CampaignSettings settings(PacingStrategy strategy) {
        return new CampaignSettings(
                "campaign-1",
                CampaignStatus.ACTIVE,
                NOW.minusSeconds(3_600),
                NOW.plusSeconds(86_400),
                strategy,
                1_000_000L,
                100_000L,
                NOW.minusSeconds(7_200),
                NOW
        );
    }
}
