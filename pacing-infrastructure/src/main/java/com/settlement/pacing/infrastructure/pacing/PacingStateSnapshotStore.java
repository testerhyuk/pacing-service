package com.settlement.pacing.infrastructure.pacing;

import com.settlement.pacing.api.gateway.PacingStateSnapshot;
import com.settlement.pacing.core.pacing.PacingState;
import com.settlement.pacing.core.pacing.Rate;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.util.Optional;

public class PacingStateSnapshotStore {
    private final PacingStateSnapshotJpaRepository repository;
    private final Clock clock;

    public PacingStateSnapshotStore(
            PacingStateSnapshotJpaRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<PacingStateSnapshot> find(String campaignId) {
        return repository.findById(campaignId)
                .map(this::toSnapshot);
    }

    public void saveIfNewer(
            String campaignId,
            PacingStateSnapshot snapshot
    ) {
        repository.saveIfNewer(
                campaignId,
                snapshot.pacingState().pacingRate().value(),
                snapshot.pacingState().updatedAt(),
                snapshot.version(),
                clock.instant()
        );
    }

    public void delete(String campaignId) {
        repository.deleteById(campaignId);
    }

    private PacingStateSnapshot toSnapshot(
            PacingStateSnapshotEntity entity
    ) {
        try {
            return new PacingStateSnapshot(
                    new PacingState(
                            new Rate(entity.getPacingRate()),
                            entity.getStateUpdatedAt()
                    ),
                    entity.getVersion()
            );
        } catch (IllegalArgumentException exception) {
            throw new DataIntegrityViolationException(
                    "저장된 페이싱 상태가 올바르지 않습니다: "
                            + entity.getCampaignId(),
                    exception
            );
        }
    }
}
