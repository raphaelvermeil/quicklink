package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.quicklink.app.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deliberately NOT @Transactional. ClickStatsWriter runs each delta in a
 * REQUIRES_NEW transaction, which by design cannot see rows an enclosing test
 * transaction has not committed — the link would be invisible, the write would hit
 * link_stats' foreign key, and the assertion would fail for the wrong reason.
 * So these tests commit for real and clean up afterwards.
 */
class ClickStatsFlusherTest extends IntegrationTest {

    @Autowired LinkService linkService;
    @Autowired ClickStatsFlusher flusher;
    @Autowired LinkStatsRepository statsRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private static final String LOCK_KEY = "stats:flush:lock";

    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void clearPendingWork() {
        redis.delete(ClickCounters.DIRTY_SET);
        redis.delete(LOCK_KEY);
    }

    @AfterEach
    void removeWhatThisTestCommitted() {
        redis.delete(ClickCounters.DIRTY_SET);
        redis.delete(LOCK_KEY);
        for (String code : created) {
            redis.delete(ClickCounters.counterKey(code));
            jdbc.update("DELETE FROM link_stats WHERE short_code = ?", code);
            jdbc.update("DELETE FROM link WHERE short_code = ?", code);
        }
        created.clear();
    }

    /** Creates a committed link and remembers it for cleanup. */
    private String newLink(String url) {
        String code = linkService.createLink(url, null).getShortCode();
        created.add(code);
        return code;
    }

    private void seedCounter(String code, long count) {
        redis.opsForValue().set(ClickCounters.counterKey(code), String.valueOf(count));
        redis.opsForSet().add(ClickCounters.DIRTY_SET, code);
    }

    @Test
    void movesCountersFromRedisIntoPostgres() {
        String code = newLink("https://example.com/flushed");
        seedCounter(code, 5);

        flusher.flush();

        assertNull(redis.opsForValue().get(ClickCounters.counterKey(code)),
                   "GETDEL must drain the counter");
        assertEquals(5L, statsRepository.findById(code).orElseThrow().getClicks());
    }

    /** The upsert must ADD to an existing row, not overwrite it. */
    @Test
    void accumulatesAcrossSuccessiveFlushes() {
        String code = newLink("https://example.com/twice");

        seedCounter(code, 3);
        flusher.flush();
        seedCounter(code, 4);
        flusher.flush();

        assertEquals(7L, statsRepository.findById(code).orElseThrow().getClicks());
    }

    @Test
    void removesTheCodeFromTheDirtySetOnceDrained() {
        String code = newLink("https://example.com/dirty");
        seedCounter(code, 2);

        flusher.flush();

        assertTrue(Boolean.FALSE.equals(redis.opsForSet().isMember(ClickCounters.DIRTY_SET, code)),
                   "drained codes must leave the dirty set");
    }

    @Test
    void isANoOpWhenNothingIsDirty() {
        redis.delete(ClickCounters.DIRTY_SET);

        flusher.flush();   // must not throw
    }

    // ---------------------------------------------------------------------
    // Multi-instance safety. @Scheduled fires on every instance, so the flush
    // must be guarded by a lock that exactly one instance can hold per cycle.
    // ---------------------------------------------------------------------

    @Test
    void skipsFlushWhileAnotherInstanceHoldsTheLock() {
        String code = newLink("https://example.com/locked");
        seedCounter(code, 6);

        // simulate a different instance already flushing
        redis.opsForValue().set(LOCK_KEY, "some-other-instance", Duration.ofSeconds(30));

        flusher.flush();

        assertEquals("6", redis.opsForValue().get(ClickCounters.counterKey(code)),
                     "counter must NOT be drained while another instance holds the lock");
        assertTrue(statsRepository.findById(code).isEmpty(),
                   "nothing should have been written to Postgres");

        redis.delete(LOCK_KEY);
        redis.delete(ClickCounters.counterKey(code));
    }

    @Test
    void releasesTheLockAfterFlushing() {
        String code = newLink("https://example.com/unlocked");
        seedCounter(code, 2);

        flusher.flush();

        assertFalse(Boolean.TRUE.equals(redis.hasKey(LOCK_KEY)),
                    "the lock must be released so the next cycle can run");
        assertEquals(2L, statsRepository.findById(code).orElseThrow().getClicks());
    }
}
