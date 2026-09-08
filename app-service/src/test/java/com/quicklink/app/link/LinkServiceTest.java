package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LinkServiceTest {

    private static final String KEY_PREFIX = "link:code:";

    @Autowired
    LinkService linkService;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void createsLinkWithGeneratedShortCode() {
        Link link = linkService.createLink("https://example.com/some/very/long/path", null);

        assertNotNull(link.getId());
        assertNotNull(link.getShortCode());
        assertTrue(link.getShortCode().length() >= 5);
        assertEquals(TargetType.URL, link.getTargetType());
        assertEquals("https://example.com/some/very/long/path", link.getTargetUrl());
    }

    @Test
    void assignsDistinctCodesToDifferentLinks() {
        Link a = linkService.createLink("https://example.com/a", null);
        Link b = linkService.createLink("https://example.com/b", null);

        assertNotEquals(a.getShortCode(), b.getShortCode());
    }

    /**
     * The only test that forces the INSERT to reach Postgres: resolve() runs a query,
     * and a query flushes pending writes first. This is what caught the
     * "short_code violates not-null constraint" bug.
     */
    @Test
    void resolvesACreatedLink() {
        Link created = linkService.createLink("https://example.com/x", null);

        assertEquals("https://example.com/x",
                     linkService.resolve(created.getShortCode()).getTargetUrl());
    }

    @Test
    void throwsWhenCodeDoesNotExist() {
        assertThrows(LinkNotFoundException.class, () -> linkService.resolve("nope99"));
    }

    /** Guards the `expiresAt != null &&` check in resolve(). */
    @Test
    void throwsWhenLinkHasExpired() {
        Link expired = linkService.createLink("https://example.com/old",
                                              Instant.now().minusSeconds(60));

        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolve(expired.getShortCode()));
    }

    @Test
    void resolvesLinkWithNoExpiry() {
        Link permanent = linkService.createLink("https://example.com/forever", null);

        assertEquals("https://example.com/forever",
                     linkService.resolve(permanent.getShortCode()).getTargetUrl());
    }

    // ---------------------------------------------------------------------
    // Cache-aside behaviour.
    //
    // NOTE: this class is @Transactional so Postgres rolls back after each
    // test, but Redis has no transaction and does NOT roll back. Every test
    // below clears its own keys at both ends so runs stay independent.
    // ---------------------------------------------------------------------

    @Test
    void cachesTheTargetUrlAfterFirstResolve() {
        Link link = linkService.createLink("https://example.com/cached", null);
        String key = KEY_PREFIX + link.getShortCode();
        redis.delete(key);

        assertNull(redis.opsForValue().get(key), "precondition: nothing cached yet");

        linkService.resolveTargetUrl(link.getShortCode());

        assertEquals("https://example.com/cached", redis.opsForValue().get(key));
        redis.delete(key);
    }

    /**
     * Proves a cache hit never reaches Postgres, without needing a mock:
     * "ghost1" exists in Redis but not in the database, so any DB lookup
     * would throw LinkNotFoundException instead of returning the value.
     */
    @Test
    void servesFromCacheWithoutConsultingTheDatabase() {
        String key = KEY_PREFIX + "ghost1";
        redis.opsForValue().set(key, "https://example.com/from-cache-only",
                                Duration.ofSeconds(30));

        assertEquals("https://example.com/from-cache-only",
                     linkService.resolveTargetUrl("ghost1"));

        redis.delete(key);
    }

    /** Cache penetration: an unknown code must be remembered as missing. */
    @Test
    void cachesTheAbsenceOfAnUnknownCode() {
        String key = KEY_PREFIX + "nope99";
        redis.delete(key);

        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolveTargetUrl("nope99"));

        assertNotNull(redis.opsForValue().get(key),
                      "a miss should leave a negative cache entry");
        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolveTargetUrl("nope99"));

        redis.delete(key);
    }

    /**
     * Guards the min(MAX_TTL, timeUntilExpiry) rule. Without it a link
     * expiring in 2 minutes would keep redirecting for the full hour.
     */
    @Test
    void cacheTtlNeverOutlivesTheLink() {
        Link shortLived = linkService.createLink("https://example.com/short-lived",
                                                 Instant.now().plusSeconds(120));
        String key = KEY_PREFIX + shortLived.getShortCode();
        redis.delete(key);

        linkService.resolveTargetUrl(shortLived.getShortCode());

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 120,
                   "TTL was " + ttl + "s; must not exceed the link's 120s remaining life");
        redis.delete(key);
    }

    @Test
    void expiredLinkIsNotServedFromTheRedirectPath() {
        Link expired = linkService.createLink("https://example.com/old",
                                              Instant.now().minusSeconds(60));
        String key = KEY_PREFIX + expired.getShortCode();
        redis.delete(key);

        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolveTargetUrl(expired.getShortCode()));

        redis.delete(key);
    }

    // ---------------------------------------------------------------------
    // Click counting (piece 3)
    // ---------------------------------------------------------------------

    @Test
    void countsAClickOnEverySuccessfulResolve() {
        Link link = linkService.createLink("https://example.com/clicked", null);
        String code = link.getShortCode();
        redis.delete(ClickCounters.counterKey(code));

        linkService.resolveTargetUrl(code);   // database path
        linkService.resolveTargetUrl(code);   // cache path

        assertEquals("2", redis.opsForValue().get(ClickCounters.counterKey(code)),
                     "both the DB path and the cache path must count");

        redis.delete(ClickCounters.counterKey(code));
        redis.opsForSet().remove(ClickCounters.DIRTY_SET, code);
        redis.delete(KEY_PREFIX + code);
    }

    /**
     * Regression guard: recordClick() must run only after a successful resolve.
     * Counting a 404 would also queue a code with no `link` row, and the flush
     * job's FK to link(short_code) would then fail and roll back the whole batch.
     */
    @Test
    void doesNotCountAClickForAnUnknownCode() {
        redis.delete(KEY_PREFIX + "nope99");
        redis.delete(ClickCounters.counterKey("nope99"));
        redis.opsForSet().remove(ClickCounters.DIRTY_SET, "nope99");

        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolveTargetUrl("nope99"));

        assertNull(redis.opsForValue().get(ClickCounters.counterKey("nope99")),
                   "a 404 is not a click");
        assertFalse(Boolean.TRUE.equals(
                        redis.opsForSet().isMember(ClickCounters.DIRTY_SET, "nope99")),
                    "an unknown code must never reach the flush queue");

        redis.delete(KEY_PREFIX + "nope99");
    }

    @Test
    void clickCountSumsPersistedAndPendingCounts() {
        Link link = linkService.createLink("https://example.com/summed", null);
        String code = link.getShortCode();
        redis.delete(ClickCounters.counterKey(code));

        linkService.resolveTargetUrl(code);          // 1 pending in Redis, 0 persisted

        assertEquals(1L, linkService.clickCount(code));

        redis.delete(ClickCounters.counterKey(code));
        redis.opsForSet().remove(ClickCounters.DIRTY_SET, code);
        redis.delete(KEY_PREFIX + code);
    }

    @Test
    void clickCountThrowsForAnUnknownCode() {
        assertThrows(LinkNotFoundException.class, () -> linkService.clickCount("nope98"));
    }
}
