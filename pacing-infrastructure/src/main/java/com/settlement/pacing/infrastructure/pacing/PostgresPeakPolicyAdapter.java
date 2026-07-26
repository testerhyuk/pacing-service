package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.gateway.PeakPolicyGateway;
import com.settlement.pacing.core.pacing.PeakPolicy;
import com.settlement.pacing.core.pacing.PeakTimeWindow;
import com.settlement.pacing.core.pacing.TrafficWeight;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

public class PostgresPeakPolicyAdapter
        implements PeakPolicyGateway {
    private static final short POLICY_ID = 1;

    private final PeakPolicyJpaRepository repository;
    private final Clock clock;

    public PostgresPeakPolicyAdapter(
            PeakPolicyJpaRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PeakPolicy> find() {
        return repository.findById(POLICY_ID)
                .map(entity -> new PeakPolicy(
                        new PeakTimeWindow(
                                entity.getStartTime(),
                                entity.getEndTime(),
                                ZoneId.of(entity.getZoneId())
                        ),
                        new TrafficWeight(
                                entity.getNormalWeight(),
                                entity.getPeakWeight()
                        )
                ));
    }

    @Override
    @Transactional
    public void save(PeakPolicy peakPolicy) {
        if (peakPolicy == null) {
            throw new IllegalArgumentException(
                    "피크 정책은 null일 수 없습니다"
            );
        }

        repository.upsert(
                peakPolicy.timeWindow().startTime(),
                peakPolicy.timeWindow().endTime(),
                peakPolicy.timeWindow().zoneId().getId(),
                peakPolicy.trafficWeight().normalWeight(),
                peakPolicy.trafficWeight().peakWeight(),
                clock.instant()
        );
    }
}
