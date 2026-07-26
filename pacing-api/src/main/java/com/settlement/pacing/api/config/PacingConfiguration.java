package com.settlement.pacing.api.config;

import com.settlement.pacing.core.pacing.ActualSpendRateCalculator;
import com.settlement.pacing.core.pacing.AsapPacingPolicy;
import com.settlement.pacing.core.pacing.AsapTargetSpendRateCalculator;
import com.settlement.pacing.core.pacing.EvenPacingPolicy;
import com.settlement.pacing.core.pacing.EvenTargetSpendRateCalculator;
import com.settlement.pacing.core.pacing.CapacityBasedPacingRateCalculator;
import com.settlement.pacing.core.pacing.PacingEngine;
import com.settlement.pacing.core.pacing.PacingPolicyResolver;
import com.settlement.pacing.core.pacing.PeakPolicyProvider;
import com.settlement.pacing.core.pacing.PeakWeightedPacingPolicy;
import com.settlement.pacing.core.pacing.PeakWeightedTargetSpendRateCalculator;
import com.settlement.pacing.core.pacing.TargetSpendRateCalculatorResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PacingConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EvenTargetSpendRateCalculator
    evenTargetSpendRateCalculator() {
        return new EvenTargetSpendRateCalculator();
    }

    @Bean
    public PeakWeightedTargetSpendRateCalculator
    peakWeightedTargetSpendRateCalculator(
            PeakPolicyProvider peakPolicyProvider
    ) {
        return new PeakWeightedTargetSpendRateCalculator(
                peakPolicyProvider
        );
    }

    @Bean
    public AsapTargetSpendRateCalculator
    asapTargetSpendRateCalculator() {
        return new AsapTargetSpendRateCalculator();
    }

    @Bean
    public TargetSpendRateCalculatorResolver
    targetSpendRateCalculatorResolver(
            EvenTargetSpendRateCalculator evenCalculator,
            PeakWeightedTargetSpendRateCalculator peakWeightedCalculator,
            AsapTargetSpendRateCalculator asapCalculator
    ) {
        return new TargetSpendRateCalculatorResolver(
                evenCalculator,
                peakWeightedCalculator,
                asapCalculator
        );
    }

    @Bean
    public EvenPacingPolicy evenPacingPolicy() {
        return new EvenPacingPolicy();
    }

    @Bean
    public PeakWeightedPacingPolicy peakWeightedPacingPolicy() {
        return new PeakWeightedPacingPolicy();
    }

    @Bean
    public AsapPacingPolicy asapPacingPolicy() {
        return new AsapPacingPolicy();
    }

    @Bean
    public PacingPolicyResolver pacingPolicyResolver(
            EvenPacingPolicy evenPacingPolicy,
            PeakWeightedPacingPolicy peakWeightedPacingPolicy,
            AsapPacingPolicy asapPacingPolicy
    ) {
        return new PacingPolicyResolver(
                evenPacingPolicy,
                peakWeightedPacingPolicy,
                asapPacingPolicy
        );
    }

    @Bean
    public CapacityBasedPacingRateCalculator
    capacityBasedPacingRateCalculator(
            PacingProperties properties
    ) {
        PacingProperties.Observation observation =
                properties.observation();

        return new CapacityBasedPacingRateCalculator(
                observation.minimumPassCount(),
                observation.smoothingFactor(),
                observation.maxRateChange(),
                observation.explorationStep()
        );
    }

    @Bean
    public ActualSpendRateCalculator
    actualSpendRateCalculator() {
        return new ActualSpendRateCalculator();
    }

    @Bean
    public PacingEngine pacingEngine(
            CapacityBasedPacingRateCalculator pacingRateCalculator,
            PacingPolicyResolver pacingPolicyResolver,
            TargetSpendRateCalculatorResolver targetResolver,
            ActualSpendRateCalculator actualSpendRateCalculator,
            PacingProperties properties
    ) {
        return new PacingEngine(
                pacingRateCalculator,
                pacingPolicyResolver,
                targetResolver,
                actualSpendRateCalculator,
                properties.rateUpdateInterval()
        );
    }
}
