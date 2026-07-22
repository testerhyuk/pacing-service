package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.PacingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetSpendRateCalculatorResolverTest {
    private final TargetSpendRateCalculator evenCalculator = (campaign, now) -> Rate.zero();

    private final TargetSpendRateCalculator peakWeightedCalculator = (campaign, now) -> Rate.zero();

    private final TargetSpendRateCalculator asapCalculator = (campaign, now) -> Rate.full();

    private final TargetSpendRateCalculatorResolver resolver =
            new TargetSpendRateCalculatorResolver(
                    evenCalculator,
                    peakWeightedCalculator,
                    asapCalculator
            );

    @Test
    void EVEN_전략이면_EVEN_목표_소진율_계산기를_반환한다() {
        TargetSpendRateCalculator result = resolver.resolve(PacingStrategy.EVEN);

        assertThat(result).isSameAs(evenCalculator);
    }

    @Test
    void PEAK_WEIGHTED_전략이면_PEAK_WEIGHTED_목표_소진율_계산기를_반환한다() {
        TargetSpendRateCalculator result = resolver.resolve(PacingStrategy.PEAK_WEIGHTED);

        assertThat(result).isSameAs(peakWeightedCalculator);
    }

    @Test
    void ASAP_전략이면_ASAP_목표_소진율_계산기를_반환한다() {
        TargetSpendRateCalculator result = resolver.resolve(PacingStrategy.ASAP);

        assertThat(result).isSameAs(asapCalculator);
    }
}