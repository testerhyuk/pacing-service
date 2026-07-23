package com.settlement.pacing.api.security;

import java.time.Duration;

public interface NonceStore {

    /**
     * nonce가 없을 때만 유효기간과 함께 저장한다.
     *
     * @return 새로 저장했으면 true, 이미 존재하면 false
     */
    boolean saveIfAbsent(
            String clientId,
            String nonce,
            Duration ttl
    );
}
