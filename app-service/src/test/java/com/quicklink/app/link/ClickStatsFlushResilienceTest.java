package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quicklink.app.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deliberately NOT @Transactional: the whole point is that each link's delta is written
 * in its own committed transaction, which a surrounding test transaction would hide.
 * Rows are cleaned up by hand instead.
 */
class ClickStatsFlushResilienceTest extends IntegrationTest {

    private static final String ORPHAN = "orph01";   // no link row will ever exist for this

    @Autowired LinkService linkService;
    @Autowired ClickStatsFlusher flusher;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private String goodCode;

    @AfterEach
    void cleanUp() {
        redis.delete(ClickCounters.DIRTY_SET);
        redis.delete(ClickCounters.counterKey(ORPHAN));
        if (goodCode != null) {
            redis.delete(ClickCounters.counterKey(goodCode));
            jdbc.update("DELETE FROM link_stats WHERE short_code = ?", goodCode);
            jdbc.update("DELETE FROM link WHERE short_code = ?", goodCode);
        }
        jdbc.update("DELETE FROM link_stats WHERE short_code = ?", ORPHAN);
    }

    private void seed(String code, long count) {
        redis.opsForValue().set(ClickCounters.counterKey(code), String.valueOf(count));
        redis.opsForSet().add(ClickCounters.DIRTY_SET, code);
    }

    /**
     * The bug this guards: a counter for a code with no link row violates link_stats'
     * foreign key. When the flush was one transaction over the whole batch, that single
     * bad code rolled back every other link's clicks — permanently, because GETDEL had
     * already consumed the counters.
     */
    @Test
    void oneUnwritableCodeDoesNotDestroyTheRestOfTheBatch() {
        goodCode = linkService.createLink("https://example.com/resilience", Instant.now().plusSeconds(3600))
                .getShortCode();
        seed(goodCode, 7);
        seed(ORPHAN, 99);

        flusher.flush();

        Long good = jdbc.queryForObject(
                "SELECT clicks FROM link_stats WHERE short_code = ?", Long.class, goodCode);
        assertEquals(7L, good, "a valid code must still be persisted alongside a broken one");

        Integer orphanRows = jdbc.queryForObject(
                "SELECT count(*) FROM link_stats WHERE short_code = ?", Integer.class, ORPHAN);
        assertEquals(0, orphanRows, "the orphan cannot be written — it has no link row");

        assertNull(redis.opsForValue().get(ClickCounters.counterKey(goodCode)),
                "drained counters are removed");
        assertTrue(Boolean.FALSE.equals(redis.opsForSet().isMember(ClickCounters.DIRTY_SET, ORPHAN)),
                "the orphan must leave the dirty set, or every future flush retries it");
    }
}
