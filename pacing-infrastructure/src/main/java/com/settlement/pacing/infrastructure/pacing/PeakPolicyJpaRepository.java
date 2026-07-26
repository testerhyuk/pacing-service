package com.settlement.pacing.infrastructure.pacing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalTime;

public interface PeakPolicyJpaRepository
        extends JpaRepository<PeakPolicyEntity, Short> {

    @Modifying
    @Query(value = """
            INSERT INTO peak_policy (
                policy_id,
                start_time,
                end_time,
                zone_id,
                normal_weight,
                peak_weight,
                updated_at
            )
            VALUES (
                1,
                :startTime,
                :endTime,
                :zoneId,
                :normalWeight,
                :peakWeight,
                :updatedAt
            )
            ON CONFLICT (policy_id)
            DO UPDATE SET
                start_time = EXCLUDED.start_time,
                end_time = EXCLUDED.end_time,
                zone_id = EXCLUDED.zone_id,
                normal_weight = EXCLUDED.normal_weight,
                peak_weight = EXCLUDED.peak_weight,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("zoneId") String zoneId,
            @Param("normalWeight") double normalWeight,
            @Param("peakWeight") double peakWeight,
            @Param("updatedAt") Instant updatedAt
    );
}
