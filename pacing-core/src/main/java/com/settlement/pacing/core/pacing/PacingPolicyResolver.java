package com.settlement.pacing.core.pacing;

import com.settlement.pacing.core.campaign.PacingStrategy;

public class PacingPolicyResolver {
    private final PacingPolicy evenPacingPolicy;
    private final PacingPolicy peakWeightedPacingPolicy;
    private final PacingPolicy asapPacingPolicy;

    public PacingPolicyResolver(
            PacingPolicy evenPacingPolicy,
            PacingPolicy peakWeightedPacingPolicy,
            PacingPolicy asapPacingPolicy
    ) {
        if (evenPacingPolicy == null || peakWeightedPacingPolicy == null || asapPacingPolicy == null) {
            throw new IllegalArgumentException("Pacing 정책은 null일 수 없습니다");
        }

        this.evenPacingPolicy = evenPacingPolicy;
        this.peakWeightedPacingPolicy = peakWeightedPacingPolicy;
        this.asapPacingPolicy = asapPacingPolicy;
    }

    public PacingPolicy resolve(PacingStrategy pacingStrategy) {
        if (pacingStrategy == null) {
            throw new IllegalArgumentException("Pacing 전략은 null일 수 없습니다");
        }

        return switch (pacingStrategy) {
            case EVEN -> evenPacingPolicy;
            case PEAK_WEIGHTED -> peakWeightedPacingPolicy;
            case ASAP -> asapPacingPolicy;
        };
    }
}
