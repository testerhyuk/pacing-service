package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class PacingPolicyResolverTest {
    private final PacingPolicy evenPacingPolicy = new EvenPacingPolicy();

    private final PacingPolicy peakWeightedPacingPolicy = new PeakWeightedPacingPolicy();

    private final PacingPolicy asapPacingPolicy = new AsapPacingPolicy();

    private final PacingPolicyResolver resolver =
            new PacingPolicyResolver(
                    evenPacingPolicy,
                    peakWeightedPacingPolicy,
                    asapPacingPolicy
            );

    @Test
    void EVEN_전략이면_EvenPacingPolicy를_반환한다() {
        PacingPolicy result =
                resolver.resolve(PacingStrategy.EVEN);

        assertThat(result).isSameAs(evenPacingPolicy);
    }

    @Test
    void PEAK_WEIGHTED_전략이면_PeakWeightedPacingPolicy를_반환한다() {
        PacingPolicy result =
                resolver.resolve(PacingStrategy.PEAK_WEIGHTED);

        assertThat(result).isSameAs(peakWeightedPacingPolicy);
    }

    @Test
    void ASAP_전략이면_AsapPacingPolicy를_반환한다() {
        PacingPolicy result =
                resolver.resolve(PacingStrategy.ASAP);

        assertThat(result).isSameAs(asapPacingPolicy);
    }
}