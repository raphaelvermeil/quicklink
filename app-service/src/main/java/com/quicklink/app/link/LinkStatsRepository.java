package com.quicklink.app.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkStatsRepository extends JpaRepository<LinkStats, String> {

    @Modifying
    @Query(value = """
            INSERT INTO link_stats (short_code, clicks, updated_at)
            VALUES (:code, :delta, now())
            ON CONFLICT (short_code)
            DO UPDATE SET clicks = link_stats.clicks + EXCLUDED.clicks,
                          updated_at = now()
            """, nativeQuery = true)
    void addClicks(@Param("code") String code, @Param("delta") long delta);
}
