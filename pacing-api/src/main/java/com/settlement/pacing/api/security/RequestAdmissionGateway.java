package com.settlement.pacing.api.security;

import java.time.Duration;

public interface RequestAdmissionGateway {

    /**
     * nonce 중복 검사와 Rate Limit 검사를 한 번에 수행한다.
     */
    Result admit(
            String clientId,
            String nonce,
            Duration nonceTtl
    );

    enum Result {
        ALLOWED,
        NONCE_REUSED,
        RATE_LIMITED
    }
}