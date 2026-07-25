package com.settlement.pacing.api.security;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.HmacSecurityProperties;
import com.settlement.pacing.api.error.ErrorCode;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HmacAuthenticationFilterTest {
    private static final String PATH =
            "/internal/v1/pacing/decisions/decide";
    private static final String CLIENT_ID = "ad-server";
    private static final String CURRENT_KEY =
            "current-secret-key-with-32-bytes-minimum";
    private static final String PREVIOUS_KEY =
            "previous-secret-key-with-32-bytes-minimum";
    private static final String NONCE = "nonce-1";
    private static final String SIGNATURE = "aabb";
    private static final String CANONICAL_REQUEST = "canonical-request";
    private static final Instant NOW =
            Instant.parse("2026-07-23T03:00:00Z");
    private static final byte[] BODY =
            "{\"requestId\":\"request-1\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private CanonicalRequestBuilder canonicalRequestBuilder;
    private HmacSignatureVerifier signatureVerifier;
    private NonceStore nonceStore;
    private PacingApiMetrics metrics;
    private AuditLogger auditLogger;
    private ClientRateLimiter clientRateLimiter;
    private SecurityErrorResponseWriter errorResponseWriter;
    private FilterChain filterChain;
    private HmacAuthenticationFilter filter;
    private HmacSecurityProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        canonicalRequestBuilder = mock(CanonicalRequestBuilder.class);
        signatureVerifier = mock(HmacSignatureVerifier.class);
        nonceStore = mock(NonceStore.class);
        metrics = mock(PacingApiMetrics.class);
        auditLogger = mock(AuditLogger.class);
        clientRateLimiter = mock(ClientRateLimiter.class);
        errorResponseWriter = mock(SecurityErrorResponseWriter.class);
        filterChain = mock(FilterChain.class);

        properties = properties(Set.of(
                ClientPermission.PACING_DECIDE,
                ClientPermission.BUDGET_RESERVE
        ));

        filter = new HmacAuthenticationFilter(
                canonicalRequestBuilder,
                signatureVerifier,
                nonceStore,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                metrics,
                auditLogger,
                clientRateLimiter,
                errorResponseWriter
        );

        when(canonicalRequestBuilder.build(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(byte[].class)
        )).thenReturn(CANONICAL_REQUEST);
        when(signatureVerifier.verify(
                CANONICAL_REQUEST,
                SIGNATURE,
                CURRENT_KEY
        )).thenReturn(true);
        when(nonceStore.saveIfAbsent(
                CLIENT_ID,
                NONCE,
                Duration.ofMinutes(2)
        )).thenReturn(true);
        when(clientRateLimiter.tryAcquire(CLIENT_ID))
                .thenReturn(true);

        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(0);
            HttpStatus status = invocation.getArgument(1);
            response.setStatus(status.value());
            return null;
        }).when(errorResponseWriter).write(
                any(HttpServletResponse.class),
                any(HttpStatus.class),
                any(ErrorCode.class),
                anyString(),
                anyString()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 정상_서명은_clientId와_권한을_인증정보에_등록한다()
            throws Exception {
        MockHttpServletRequest request = signedRequest();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();
        assertThat(authentication.getName()).isEqualTo(CLIENT_ID);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        "PACING_DECIDE",
                        "BUDGET_RESERVE"
                );
        verify(filterChain).doFilter(
                any(HttpServletRequest.class),
                eq(response)
        );
    }

    @Test
    void 필터가_읽은_요청_본문을_다음_필터에서도_다시_읽을_수_있다()
            throws Exception {
        MockHttpServletRequest request = signedRequest();
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        ArgumentCaptor<HttpServletRequest> requestCaptor =
                ArgumentCaptor.forClass(HttpServletRequest.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(
                requestCaptor.capture(),
                eq(response)
        );
        assertThat(
                requestCaptor.getValue()
                        .getInputStream()
                        .readAllBytes()
        ).isEqualTo(BODY);
    }

    @Test
    void 필수_헤더가_없으면_401로_거절하고_감사_로그를_기록한다()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "MISSING_HEADER"
        );
        ArgumentCaptor<AuditLogger.AuditEvent> eventCaptor =
                ArgumentCaptor.forClass(
                        AuditLogger.AuditEvent.class
                );
        verify(auditLogger).log(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(
                        AuditLogger.EventType.AUTHENTICATION_FAILURE
                );
        verify(filterChain, never()).doFilter(
                any(),
                any()
        );
    }

    @Test
    void 허용_시간을_벗어난_timestamp는_401로_거절한다()
            throws Exception {
        MockHttpServletRequest request = signedRequest();
        request.removeHeader(
                HmacAuthenticationFilter.TIMESTAMP_HEADER
        );
        request.addHeader(
                HmacAuthenticationFilter.TIMESTAMP_HEADER,
                NOW.minusSeconds(61).getEpochSecond()
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "TIMESTAMP_EXPIRED"
        );
        verify(signatureVerifier, never()).verify(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void 숫자가_아닌_timestamp는_INVALID_TIMESTAMP로_거절한다()
            throws Exception {
        MockHttpServletRequest request = signedRequest();
        request.removeHeader(
                HmacAuthenticationFilter.TIMESTAMP_HEADER
        );
        request.addHeader(
                HmacAuthenticationFilter.TIMESTAMP_HEADER,
                "invalid"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "INVALID_TIMESTAMP"
        );
    }

    @Test
    void 등록되지_않은_clientId는_401로_거절한다()
            throws Exception {
        MockHttpServletRequest request = signedRequest();
        request.removeHeader(
                HmacAuthenticationFilter.CLIENT_ID_HEADER
        );
        request.addHeader(
                HmacAuthenticationFilter.CLIENT_ID_HEADER,
                "unknown-client"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "UNKNOWN_CLIENT"
        );
    }

    @Test
    void 잘못된_서명은_nonce를_저장하지_않고_401로_거절한다()
            throws Exception {
        when(signatureVerifier.verify(
                CANONICAL_REQUEST,
                SIGNATURE,
                CURRENT_KEY
        )).thenReturn(false);
        when(signatureVerifier.verify(
                CANONICAL_REQUEST,
                SIGNATURE,
                PREVIOUS_KEY
        )).thenReturn(false);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "INVALID_SIGNATURE"
        );
        verify(nonceStore, never()).saveIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    void 이전_키의_정상_서명도_허용한다() throws Exception {
        when(signatureVerifier.verify(
                CANONICAL_REQUEST,
                SIGNATURE,
                CURRENT_KEY
        )).thenReturn(false);
        when(signatureVerifier.verify(
                CANONICAL_REQUEST,
                SIGNATURE,
                PREVIOUS_KEY
        )).thenReturn(true);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        verify(filterChain).doFilter(
                any(HttpServletRequest.class),
                eq(response)
        );
    }

    @Test
    void 재사용된_nonce는_401로_거절한다() throws Exception {
        when(nonceStore.saveIfAbsent(
                CLIENT_ID,
                NONCE,
                Duration.ofMinutes(2)
        )).thenReturn(false);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).recordAuthenticationFailure(
                "NONCE_REUSED"
        );
        verify(clientRateLimiter, never())
                .tryAcquire(anyString());
    }

    @Test
    void Rate_Limit을_초과하면_429로_거절한다()
            throws Exception {
        when(clientRateLimiter.tryAcquire(CLIENT_ID))
                .thenReturn(false);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        assertThat(response.getStatus()).isEqualTo(429);
        verify(metrics).recordRateLimitRejection(CLIENT_ID);
        verify(errorResponseWriter).write(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMIT_EXCEEDED,
                "요청 허용량을 초과했습니다",
                PATH
        );
    }

    @Test
    void nonce_저장소_장애는_503으로_응답한다()
            throws Exception {
        when(nonceStore.saveIfAbsent(
                CLIENT_ID,
                NONCE,
                Duration.ofMinutes(2)
        )).thenThrow(new DataAccessResourceFailureException(
                "Redis unavailable"
        ));
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        assertThat(response.getStatus()).isEqualTo(503);
        verify(errorResponseWriter).write(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.STORAGE_UNAVAILABLE,
                "인증 상태 저장소를 사용할 수 없습니다",
                PATH
        );
        verify(clientRateLimiter, never())
                .tryAcquire(anyString());
    }

    @Test
    void Rate_Limit_저장소_장애는_503으로_응답한다()
            throws Exception {
        when(clientRateLimiter.tryAcquire(CLIENT_ID))
                .thenThrow(new DataAccessResourceFailureException(
                        "Redis unavailable"
                ));
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                signedRequest(),
                response,
                filterChain
        );

        assertThat(response.getStatus()).isEqualTo(503);
        verify(errorResponseWriter).write(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.STORAGE_UNAVAILABLE,
                "인증 상태 저장소를 사용할 수 없습니다",
                PATH
        );
        verify(filterChain, never()).doFilter(
                any(),
                any()
        );
    }

    @Test
    void 보호_대상이_아닌_경로에서는_HMAC_검사를_하지_않는다()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/unknown");
        request.setRequestURI("/unknown");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(signatureVerifier, never()).verify(
                anyString(),
                anyString(),
                anyString()
        );
    }

    private MockHttpServletRequest signedRequest() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setContent(BODY);
        request.addHeader(
                HmacAuthenticationFilter.CLIENT_ID_HEADER,
                CLIENT_ID
        );
        request.addHeader(
                HmacAuthenticationFilter.TIMESTAMP_HEADER,
                String.valueOf(NOW.getEpochSecond())
        );
        request.addHeader(
                HmacAuthenticationFilter.NONCE_HEADER,
                NONCE
        );
        request.addHeader(
                HmacAuthenticationFilter.SIGNATURE_HEADER,
                SIGNATURE
        );
        return request;
    }

    private HmacSecurityProperties properties(
            Set<ClientPermission> permissions
    ) {
        HmacSecurityProperties.Client client =
                new HmacSecurityProperties.Client(
                        CURRENT_KEY,
                        PREVIOUS_KEY,
                        permissions
                );

        return new HmacSecurityProperties(
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                Map.of(CLIENT_ID, client)
        );
    }
}
