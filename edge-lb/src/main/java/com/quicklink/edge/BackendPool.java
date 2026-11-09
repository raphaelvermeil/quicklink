package com.quicklink.edge;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the backends and makes the load-balancing decision. Selection only ever
 * considers healthy backends, which is what makes failover fall out for free.
 */
final class BackendPool {

    enum Strategy {
        ROUND_ROBIN,
        LEAST_CONNECTIONS
    }

    private final List<Backend> backends;
    private final Strategy strategy;
    private final AtomicInteger cursor = new AtomicInteger();

    BackendPool(List<Backend> backends, Strategy strategy) {
        this.backends = List.copyOf(backends);
        this.strategy = strategy;
    }

    List<Backend> all() {
        return backends;
    }

    /** @return a healthy backend, or null when every backend is down (caller should 503). */
    Backend next() {
        List<Backend> healthy = backends.stream().filter(Backend::isHealthy).toList();
        if (healthy.isEmpty()) {
            return null;
        }
        return switch (strategy) {
            // floorMod, not %, so selection keeps working after the cursor overflows to negative.
            case ROUND_ROBIN -> healthy.get(Math.floorMod(cursor.getAndIncrement(), healthy.size()));
            case LEAST_CONNECTIONS -> healthy.stream()
                    .min(Comparator.comparingInt(Backend::inFlight))
                    .orElseThrow();
        };
    }
}
