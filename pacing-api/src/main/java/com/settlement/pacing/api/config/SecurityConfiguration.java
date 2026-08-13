package com.settlement.pacing.api.config;

import com.settlement.pacing.api.audit.AuditLogger;
import com.settlement.pacing.api.error.ErrorCode;
import com.settlement.pacing.api.monitoring.PacingApiMetrics;
import com.settlement.pacing.api.monitoring.StorageAvailabilityMonitor;
import com.settlement.pacing.api.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;

import java.time.Clock;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private static final Logger log =
            LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CanonicalRequestBuilder canonicalRequestBuilder,
            HmacSignatureVerifier signatureVerifier,
            HmacSecurityProperties properties,
            Clock clock,
            PacingApiMetrics metrics,
            AuditLogger auditLogger,
            SecurityErrorResponseWriter errorResponseWriter,
            RequestAdmissionGateway requestAdmissionGateway,
            StorageAvailabilityMonitor storageAvailabilityMonitor
    ) throws Exception {
        HmacAuthenticationFilter hmacAuthenticationFilter =
                new HmacAuthenticationFilter(
                        canonicalRequestBuilder,
                        signatureVerifier,
                        properties,
                        clock,
                        metrics,
                        auditLogger,
                        errorResponseWriter,
                        requestAdmissionGateway,
                        storageAvailabilityMonitor
                );

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .requestMatchers(
                                "/internal/v1/pacing/decisions/**"
                        ).hasAuthority(
                                ClientPermission.PACING_DECIDE.name()
                        )
                        .requestMatchers(
                                "/internal/v1/budget-reservations/**"
                        ).hasAuthority(
                                ClientPermission.BUDGET_RESERVE.name()
                        )
                        .requestMatchers(
                                "/internal/admin/**"
                        ).hasAuthority(
                                ClientPermission.ADMIN.name()
                        )
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, cause) ->
                                        errorResponseWriter.write(
                                                response,
                                                HttpStatus.UNAUTHORIZED,
                                                ErrorCode.AUTHENTICATION_FAILED,
                                                "인증이 필요합니다",
                                                request.getRequestURI()
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, cause) -> {
                                    recordAuthorizationFailure(
                                            request,
                                            auditLogger,
                                            clock
                                    );
                                    errorResponseWriter.write(
                                            response,
                                            HttpStatus.FORBIDDEN,
                                            ErrorCode.ACCESS_DENIED,
                                            "요청 권한이 없습니다",
                                            request.getRequestURI()
                                    );
                                }
                        )
                )
                .addFilterBefore(
                        hmacAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    private void recordAuthorizationFailure(
            HttpServletRequest request,
            AuditLogger auditLogger,
            Clock clock
    ) {
        String clientId = request.getUserPrincipal() == null
                ? null
                : request.getUserPrincipal().getName();

        try {
            auditLogger.log(new AuditLogger.AuditEvent(
                    AuditLogger.EventType.AUTHORIZATION_FAILURE,
                    clientId,
                    request.getHeader(
                            HmacAuthenticationFilter.NONCE_HEADER
                    ),
                    request.getRequestURI(),
                    null,
                    null,
                    AuditLogger.Result.FAILURE,
                    "INSUFFICIENT_PERMISSION",
                    clock.instant()
            ));
        } catch (RuntimeException exception) {
            log.error(
                    "권한 거절 감사 로그 저장 중 오류가 발생했습니다",
                    exception
            );
        }
    }
}
