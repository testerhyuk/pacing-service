package com.settlement.pacing.api.config;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.decision.application.PacingDecisionCommand;
import com.settlement.pacing.api.decision.application.PacingDecisionResult;
import com.settlement.pacing.api.decision.application.PacingDecisionService;
import com.settlement.pacing.api.decision.web.PacingDecisionController;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.api.reservation.application.BudgetReservationService;
import com.settlement.pacing.api.security.*;
import com.settlement.pacing.core.pacing.DecisionReason;
import com.settlement.pacing.core.pacing.DecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PacingDecisionController.class)
@Import({
        SecurityConfiguration.class,
        SecurityErrorResponseWriter.class
})
class SecurityConfigurationTest {
    private static final String DECISION_ENDPOINT =
            "/internal/v1/pacing/decisions/decide";
    private static final Instant NOW =
            Instant.parse("2026-07-23T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PacingDecisionService pacingDecisionService;

    @MockitoBean
    private BudgetReservationService budgetReservationService;

    @MockitoBean
    private CanonicalRequestBuilder canonicalRequestBuilder;

    @MockitoBean
    private HmacSignatureVerifier signatureVerifier;

    @MockitoBean
    private HmacSecurityProperties properties;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private PacingApiMetrics metrics;

    @MockitoBean
    private AuditLogger auditLogger;

    @MockitoBean
    private RequestAdmissionGateway requestAdmissionGateway;

    @BeforeEach
    void setUp() {
        HmacSecurityProperties.Client adServer =
                new HmacSecurityProperties.Client(
                        "ad-server-secret-with-32-bytes-minimum",
                        null,
                        null,
                        Set.of(ClientPermission.PACING_DECIDE)
                );

        when(clock.instant()).thenReturn(NOW);
        when(properties.timestampTolerance())
                .thenReturn(Duration.ofSeconds(60));
        when(properties.nonceTtl())
                .thenReturn(Duration.ofMinutes(2));
        when(properties.maxRequestBodyBytes())
                .thenReturn(65_536);
        when(properties.findClient("ad-server"))
                .thenReturn(Optional.of(adServer));
        when(canonicalRequestBuilder.build(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(byte[].class)
        )).thenReturn("canonical-request");
        when(signatureVerifier.verify(
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(true);
        when(requestAdmissionGateway.admit(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenReturn(
                RequestAdmissionGateway.Result.ALLOWED
        );
        when(pacingDecisionService.decide(
                any(PacingDecisionCommand.class)
        )).thenReturn(new PacingDecisionResult(
                "request-1",
                "campaign-1",
                DecisionType.PASS,
                DecisionReason.PASS,
                0.5,
                NOW
        ));
    }

    @Test
    void health_경로는_HMAC_인증_없이_접근할_수_있다()
            throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(
                        result.getResponse().getStatus()
                ).isNotIn(401, 403));
    }

    @Test
    void Prometheus_경로는_HMAC_인증_없이_접근할_수_있다()
            throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(result -> assertThat(
                        result.getResponse().getStatus()
                ).isNotIn(401, 403));
    }

    @Test
    void 페이싱_판단_API에_인증_헤더가_없으면_401이다()
            throws Exception {
        mockMvc.perform(post(DECISION_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDecisionRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("AUTHENTICATION_FAILED"));
    }

    @Test
    void PACING_DECIDE_권한으로_페이싱_판단_API를_호출할_수_있다()
            throws Exception {
        mockMvc.perform(signedPost(
                        DECISION_ENDPOINT,
                        "ad-server",
                        validDecisionRequest()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"));
    }

    @Test
    void PACING_DECIDE_권한이_없으면_403이다()
            throws Exception {
        HmacSecurityProperties.Client auctionServer =
                new HmacSecurityProperties.Client(
                        "auction-server-secret-with-32-bytes-minimum",
                        null,
                        null,
                        Set.of(ClientPermission.BUDGET_RESERVE)
                );
        when(properties.findClient("auction-server"))
                .thenReturn(Optional.of(auctionServer));

        mockMvc.perform(signedPost(
                        DECISION_ENDPOINT,
                        "auction-server",
                        validDecisionRequest()
                ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("ACCESS_DENIED"));

        verify(auditLogger).log(
                any(AuditLogger.AuditEvent.class)
        );
    }

    @Test
    void 정의되지_않은_경로는_기본_차단한다()
            throws Exception {
        mockMvc.perform(get("/not-allowed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("AUTHENTICATION_FAILED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    signedPost(
            String path,
            String clientId,
            String body
    ) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(
                        HmacAuthenticationFilter.CLIENT_ID_HEADER,
                        clientId
                )
                .header(
                        HmacAuthenticationFilter.TIMESTAMP_HEADER,
                        NOW.getEpochSecond()
                )
                .header(
                        HmacAuthenticationFilter.NONCE_HEADER,
                        "nonce-1"
                )
                .header(
                        HmacAuthenticationFilter.SIGNATURE_HEADER,
                        "aabb"
                );
    }

    private String validDecisionRequest() {
        return """
                {
                  "requestId": "request-1",
                  "campaignId": "campaign-1",
                  "requestedAt": "2026-07-23T03:00:00Z"
                }
                """;
    }
}
