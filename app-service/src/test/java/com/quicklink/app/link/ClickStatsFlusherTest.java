package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ClickStatsFlusherTest {

    @Autowired LinkService linkService;
    @Autowired ClickStatsFlusher flusher;
    @Autowired LinkStatsRepository statsRepository;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearPendingWork() {
        // Redis does not roll back with the test transaction, so drop any dirty
        // codes left behind by other tests before exercising the flusher.
        redis.delete(ClickCounters.DIRTY_SET);
    }

    private void seedCounter(String code, long count) {
        redis.opsForValue().set(ClickCounters.counterKey(code), String.valueOf(count));
        redis.opsForSet().add(ClickCounters.DIRTY_SET, code);
    }

    @Test
    void movesCountersFromRedisIntoPostgres() {
        String code = linkService.createLink("https://example.com/flushed", null).getShortCode();
        seedCounter(code, 5);

        flusher.flush();

        assertNull(redis.opsForValue().get(ClickCounters.counterKey(code)),
                   "GETDEL must drain the counter");
        assertEquals(5L, statsRepository.findById(code).orElseThrow().getClicks());
    }

    /** The upsert must ADD to an existing row, not overwrite it. */
    @Test
    void accumulatesAcrossSuccessiveFlushes() {
        String code = linkService.createLink("https://example.com/twice", null).getShortCode();

        seedCounter(code, 3);
        flusher.flush();
        seedCounter(code, 4);
        flusher.flush();

        assertEquals(7L, statsRepository.findById(code).orElseThrow().getClicks());
    }

    @Test
    void removesTheCodeFromTheDirtySetOnceDrained() {
        String code = linkService.createLink("https://example.com/dirty", null).getShortCode();
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
}
