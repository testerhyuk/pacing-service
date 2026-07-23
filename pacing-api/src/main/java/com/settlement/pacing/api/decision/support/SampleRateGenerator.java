package com.settlement.pacing.api.decision.support;

import com.settlement.pacing.core.pacing.Rate;

public interface SampleRateGenerator {
    Rate generate(String requestId, String campaignId);
}
