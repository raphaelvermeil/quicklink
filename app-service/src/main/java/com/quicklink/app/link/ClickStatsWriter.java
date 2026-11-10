package com.quicklink.app.link;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one link's click delta in its own transaction.
 *
 * A separate bean rather than a method on the flusher: @Transactional only applies to
 * calls arriving through the Spring proxy, so a self-invocation would silently get no
 * transaction of its own and would rejoin the caller's — which is exactly the sharing
 * this class exists to avoid.
 */
@Component
class ClickStatsWriter {

    private final LinkStatsRepository statsRepository;

    ClickStatsWriter(LinkStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /**
     * REQUIRES_NEW, not the default REQUIRED: this must get its own transaction even
     * when something upstream already has one. With REQUIRED a failure here would mark
     * the caller's transaction rollback-only and take the whole batch down with it —
     * which is the behaviour this class exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void addClicks(String shortCode, long delta) {
        statsRepository.addClicks(shortCode, delta);
    }
}
