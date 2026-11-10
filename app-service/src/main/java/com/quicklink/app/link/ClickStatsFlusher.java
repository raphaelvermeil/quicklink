package com.quicklink.app.link;

import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ClickStatsFlusher {

    private static final Logger log = LoggerFactory.getLogger(ClickStatsFlusher.class);

    private static final String LOCK_KEY = "stats:flush:lock";

    /** Safety net only: the normal path releases the lock in a finally block. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final String instanceId = System.getenv().getOrDefault("HOSTNAME", "local");

    private final StringRedisTemplate redis;
    private final ClickStatsWriter writer;

    public ClickStatsFlusher(StringRedisTemplate redis, ClickStatsWriter writer) {
        this.redis = redis;
        this.writer = writer;
    }

    /**
     * @Scheduled fires on EVERY instance, so three app instances would race to drain the
     * same Redis counters. SET NX lets exactly one instance win each cycle; the others
     * return immediately.
     */
    /**
     * Deliberately NOT @Transactional. Each link's delta is written in its own
     * transaction by ClickStatsWriter, so one unwritable code cannot roll back every
     * other link's clicks — and because GETDEL has already consumed the counters, such
     * a rollback would destroy them permanently.
     */
    @Scheduled(fixedDelayString = "${app.stats.flush-interval-ms:60000}")
    public void flush() {
        Boolean won = redis.opsForValue().setIfAbsent(LOCK_KEY, instanceId, LOCK_TTL);
        if (!Boolean.TRUE.equals(won)) {
            return;                       // another instance is flushing this cycle
        }
        try {
            drainCounters();
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    private void drainCounters() {
        Set<String> codes = redis.opsForSet().members(ClickCounters.DIRTY_SET);
        if (codes == null || codes.isEmpty()) {
            return;
        }

        int flushed = 0;
        int dropped = 0;
        for (String code : codes) {
            redis.opsForSet().remove(ClickCounters.DIRTY_SET, code);          // SREM first
            String value = redis.opsForValue().getAndDelete(ClickCounters.counterKey(code));
            if (value == null) {
                continue;
            }
            long delta = Long.parseLong(value);
            if (delta <= 0) {
                continue;
            }
            try {
                writer.addClicks(code, delta);
                flushed++;
            } catch (DataAccessException e) {
                // Most likely a counter for a link that no longer exists, which would
                // violate link_stats' foreign key. The delta is already gone from Redis,
                // so log and move on rather than lose the rest of the batch with it.
                dropped++;
                log.warn("dropping {} click(s) for unknown code {}: {}",
                        delta, code, e.getMostSpecificCause().getMessage());
            }
        }
        if (flushed > 0 || dropped > 0) {
            log.info("Flushed click counts for {} link(s){}", flushed,
                    dropped > 0 ? ", dropped " + dropped : "");
        }
    }
}
