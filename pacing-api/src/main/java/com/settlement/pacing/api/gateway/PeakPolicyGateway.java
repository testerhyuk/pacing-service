package com.settlement.pacing.api.gateway;

import com.settlement.pacing.core.pacing.PeakPolicy;

import java.util.Optional;

public interface PeakPolicyGateway {

    Optional<PeakPolicy> find();

    void save(PeakPolicy peakPolicy);
}
