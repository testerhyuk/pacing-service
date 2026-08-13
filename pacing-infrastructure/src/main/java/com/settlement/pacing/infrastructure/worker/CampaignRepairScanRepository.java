package com.settlement.pacing.infrastructure.worker;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;

public class CampaignRepairScanRepository {
    private final JdbcClient jdbcClient;

    public CampaignRepairScanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 마지막으로 확인한 캠페인 다음부터 조회하고, 끝에 도달하면 처음으로 돌아간다.
     */
    public List<String> findNext(
            String cursor,
            int limit
    ) {
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException(
                    "캠페인 조회 cursor는 비어있을 수 없습니다"
            );
        }

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "캠페인 조회 limit은 0보다 커야 합니다"
            );
        }

        if (cursor == null) {
            return findFirst(limit);
        }

        List<String> result = new ArrayList<>(limit);
        result.addAll(findAfter(cursor, limit));

        int remaining = limit - result.size();
        if (remaining > 0) {
            result.addAll(findAtOrBefore(cursor, remaining));
        }

        return List.copyOf(result);
    }

    private List<String> findFirst(int limit) {
        return jdbcClient.sql("""
                        SELECT campaign_id
                        FROM campaign
                        ORDER BY campaign_id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    private List<String> findAfter(
            String cursor,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT campaign_id
                        FROM campaign
                        WHERE campaign_id > :cursor
                        ORDER BY campaign_id
                        LIMIT :limit
                        """)
                .param("cursor", cursor)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    private List<String> findAtOrBefore(
            String cursor,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT campaign_id
                        FROM campaign
                        WHERE campaign_id <= :cursor
                        ORDER BY campaign_id
                        LIMIT :limit
                        """)
                .param("cursor", cursor)
                .param("limit", limit)
                .query(String.class)
                .list();
    }
}
