package com.settlement.pacing.api.security;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.config.HmacSecurityProperties;
import com.settlement.pacing.api.error.ErrorCode;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class HmacAuthenticationFilter
        extends OncePerRequestFilter {
    private static final Logger log =
            LoggerFactory.getLogger(HmacAuthenticationFilter.class);

    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String NONCE_HEADER = "X-Nonce";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private final CanonicalRequestBuilder canonicalRequestBuilder;
    private final HmacSignatureVerifier signatureVerifier;
    private final NonceStore nonceStore;
    private final HmacSecurityProperties properties;
    private final Clock clock;
    private final PacingApiMetrics metrics;
    private final AuditLogger auditLogger;
    private final ClientRateLimiter clientRateLimiter;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public HmacAuthenticationFilter(
            CanonicalRequestBuilder canonicalRequestBuilder,
            HmacSignatureVerifier signatureVerifier,
            NonceStore nonceStore,
            HmacSecurityProperties properties,
            Clock clock,
            PacingApiMetrics metrics,
            AuditLogger auditLogger,
            ClientRateLimiter clientRateLimiter,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.canonicalRequestBuilder = canonicalRequestBuilder;
        this.signatureVerifier = signatureVerifier;
        this.nonceStore = nonceStore;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.clientRateLimiter = clientRateLimiter;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String clientId = request.getHeader(CLIENT_ID_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String providedSignature =
                request.getHeader(SIGNATURE_HEADER);

        if (isBlank(clientId)
                || isBlank(timestamp)
                || isBlank(nonce)
                || isBlank(providedSignature)) {
            reject(
                    request,
                    response,
                    clientId,
                    nonce,
                    "MISSING_HEADER"
            );
            return;
        }

        TimestampValidation timestampValidation =
                validateTimestamp(timestamp);

        if (timestampValidation != TimestampValidation.VALID) {
            reject(
                    request,
                    response,
                    clientId,
                    nonce,
                    timestampValidation.name()
            );
            return;
        }

        HmacSecurityProperties.Client client =
                properties.findClient(clientId).orElse(null);

        if (client == null) {
            reject(
                    request,
                    response,
                    clientId,
                    nonce,
                    "UNKNOWN_CLIENT"
            );
            return;
        }

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > properties.maxRequestBodyBytes()) {
            rejectBodyTooLarge(request, response);
            return;
        }

        byte[] requestBody = request.getInputStream().readNBytes(
                properties.maxRequestBodyBytes() + 1
        );
        if (requestBody.length > properties.maxRequestBodyBytes()) {
            rejectBodyTooLarge(request, response);
            return;
        }
        CachedBodyHttpServletRequest cachedRequest =
                new CachedBodyHttpServletRequest(
                        request,
                        requestBody
                );

        String canonicalRequest = canonicalRequestBuilder.build(
                request.getMethod(),
                request.getRequestURI(),
                clientId,
                timestamp,
                nonce,
                requestBody
        );

        boolean signatureVerified = client.verificationKeys(
                        clock.instant()
                )
                .stream()
                .anyMatch(secretKey -> signatureVerifier.verify(
                        canonicalRequest,
                        providedSignature,
                        secretKey
                ));

        if (!signatureVerified) {
            reject(
                    request,
                    response,
                    clientId,
                    nonce,
                    "INVALID_SIGNATURE"
            );
            return;
        }

        boolean nonceSaved;
        try {
            nonceSaved = nonceStore.saveIfAbsent(
                    clientId,
                    nonce,
                    properties.nonceTtl()
            );
        } catch (DataAccessException exception) {
            rejectStorageUnavailable(
                    request,
                    response,
                    "nonce 저장소",
                    exception
            );
            return;
        }

        if (!nonceSaved) {
            reject(
                    request,
                    response,
                    clientId,
                    nonce,
                    "NONCE_REUSED"
            );
            return;
        }

        boolean rateLimitAllowed;
        try {
            rateLimitAllowed =
                    clientRateLimiter.tryAcquire(clientId);
        } catch (DataAccessException exception) {
            rejectStorageUnavailable(
                    request,
                    response,
                    "Rate Limit 저장소",
                    exception
            );
            return;
        }

        if (!rateLimitAllowed) {
            metrics.recordRateLimitRejection(clientId);
            errorResponseWriter.write(
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "요청 허용량을 초과했습니다",
                    request.getRequestURI()
            );
            return;
        }

        List<SimpleGrantedAuthority> authorities =
                client.permissions()
                        .stream()
                        .map(permission ->
                                new SimpleGrantedAuthority(
                                        permission.name()
                                )
                        )
                        .toList();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        clientId,
                        null,
                        authorities
                );

        SecurityContext securityContext =
                SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        filterChain.doFilter(cachedRequest, response);
    }

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {
        String requestUri = request.getRequestURI();

        return !matchesPath(
                requestUri,
                "/internal/v1/pacing/decisions"
        )
                && !matchesPath(
                requestUri,
                "/internal/v1/budget-reservations"
        )
                && !matchesPath(
                requestUri,
                "/internal/admin"
        );
    }

    private boolean matchesPath(
            String requestUri,
            String protectedPath
    ) {
        return requestUri.equals(protectedPath)
                || requestUri.startsWith(protectedPath + "/");
    }

    private TimestampValidation validateTimestamp(
            String timestamp
    ) {
        try {
            long epochSeconds = Long.parseLong(timestamp);
            Instant requestedAt =
                    Instant.ofEpochSecond(epochSeconds);
            Duration difference = Duration.between(
                    requestedAt,
                    clock.instant()
            ).abs();

            if (difference.compareTo(
                    properties.timestampTolerance()
            ) > 0) {
                return TimestampValidation.TIMESTAMP_EXPIRED;
            }

            return TimestampValidation.VALID;
        } catch (NumberFormatException
                 | DateTimeException
                 | ArithmeticException exception) {
            return TimestampValidation.INVALID_TIMESTAMP;
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String clientId,
            String requestId,
            String reason
    ) throws IOException {
        metrics.recordAuthenticationFailure(reason);
        recordAuthenticationFailure(
                request,
                clientId,
                requestId,
                reason
        );
        errorResponseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCode.AUTHENTICATION_FAILED,
                "HMAC 인증에 실패했습니다",
                request.getRequestURI()
        );
    }

    private void recordAuthenticationFailure(
            HttpServletRequest request,
            String clientId,
            String requestId,
            String reason
    ) {
        try {
            auditLogger.log(new AuditLogger.AuditEvent(
                    AuditLogger.EventType.AUTHENTICATION_FAILURE,
                    clientId,
                    requestId,
                    request.getRequestURI(),
                    null,
                    null,
                    AuditLogger.Result.FAILURE,
                    reason,
                    clock.instant()
            ));
        } catch (RuntimeException exception) {
            log.error(
                    "인증 실패 감사 로그 저장 중 오류가 발생했습니다",
                    exception
            );
        }
    }

    private void rejectStorageUnavailable(
            HttpServletRequest request,
            HttpServletResponse response,
            String storageName,
            DataAccessException exception
    ) throws IOException {
        log.error(
                "{} 연결 오류로 HMAC 인증 요청을 처리할 수 없습니다",
                storageName,
                exception
        );
        errorResponseWriter.write(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.STORAGE_UNAVAILABLE,
                "인증 상태 저장소를 사용할 수 없습니다",
                request.getRequestURI()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void rejectBodyTooLarge(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        errorResponseWriter.write(
                response,
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCode.REQUEST_BODY_TOO_LARGE,
                "요청 본문이 허용 크기를 초과했습니다",
                request.getRequestURI()
        );
    }

    private enum TimestampValidation {
        VALID,
        INVALID_TIMESTAMP,
        TIMESTAMP_EXPIRED
    }

    private static class CachedBodyHttpServletRequest
            extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyHttpServletRequest(
                HttpServletRequest request,
                byte[] body
        ) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream(body);

            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(
                        ReadListener readListener
                ) {
                    // Spring MVC의 동기 요청 처리에서는 사용하지 않는다.
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());

            return new BufferedReader(
                    new InputStreamReader(
                            getInputStream(),
                            charset
                    )
            );
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
