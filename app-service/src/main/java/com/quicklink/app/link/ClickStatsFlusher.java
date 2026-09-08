package com.quicklink.app.link;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClickStatsFlusher {

    private static final Logger log = LoggerFactory.getLogger(ClickStatsFlusher.class);

    private final StringRedisTemplate redis;
    private final LinkStatsRepository statsRepository;

    public ClickStatsFlusher(StringRedisTemplate redis, LinkStatsRepository statsRepository) {
        this.redis = redis;
        this.statsRepository = statsRepository;
    }

    @Scheduled(fixedDelayString = "${app.stats.flush-interval-ms:60000}")
    @Transactional
    public void flush() {
        Set<String> codes = redis.opsForSet().members(ClickCounters.DIRTY_SET);
        if (codes == null || codes.isEmpty()) {
            return;
        }

        int flushed = 0;
        for (String code : codes) {
            redis.opsForSet().remove(ClickCounters.DIRTY_SET, code);          // SREM first
            String value = redis.opsForValue().getAndDelete(ClickCounters.counterKey(code));
            if (value == null) {
                continue;
            }
            long delta = Long.parseLong(value);
            if (delta > 0) {
                statsRepository.addClicks(code, delta);
                flushed++;
            }
        }
        if (flushed > 0) {
            log.info("Flushed click counts for {} link(s)", flushed);
        }
    }
}
