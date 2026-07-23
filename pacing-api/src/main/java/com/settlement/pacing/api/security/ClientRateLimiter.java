package com.settlement.pacing.api.security;

public interface ClientRateLimiter {

    /**
     * 해당 clientId가 현재 요청을 처리할 수 있는지 확인한다.
     *
     * @return 허용하면 true, 제한을 초과했으면 false
     */
    boolean tryAcquire(String clientId);
}
