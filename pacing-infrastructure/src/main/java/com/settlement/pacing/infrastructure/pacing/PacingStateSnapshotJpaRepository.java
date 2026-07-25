package com.settlement.pacing.infrastructure.pacing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface PacingStateSnapshotJpaRepository
        extends JpaRepository<PacingStateSnapshotEntity, String> {

    /**
     * Redis보다 늦게 도착한 이전 버전이 최신 스냅샷을 덮어쓰지 못하게 한다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO pacing_state_snapshot (
                campaign_id,
                pacing_rate,
                state_updated_at,
                version,
                persisted_at
            )
            VALUES (
                :campaignId,
                :pacingRate,
                :stateUpdatedAt,
                :version,
                :persistedAt
            )
            ON CONFLICT (campaign_id)
            DO UPDATE SET
                pacing_rate = EXCLUDED.pacing_rate,
                state_updated_at = EXCLUDED.state_updated_at,
                version = EXCLUDED.version,
                persisted_at = EXCLUDED.persisted_at
            WHERE pacing_state_snapshot.version < EXCLUDED.version
            """, nativeQuery = true)
    int saveIfNewer(
            @Param("campaignId") String campaignId,
            @Param("pacingRate") double pacingRate,
            @Param("stateUpdatedAt") Instant stateUpdatedAt,
            @Param("version") long version,
            @Param("persistedAt") Instant persistedAt
    );
}
