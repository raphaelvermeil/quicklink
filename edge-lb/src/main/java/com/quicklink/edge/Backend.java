package com.quicklink.edge;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One app instance the balancer can route to. Both mutable fields are read and
 * written from many request threads at once, hence the atomics.
 */
final class Backend {

    private final String address;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();

    Backend(String address) {
        this.address = address;
    }

    String address() {
        return address;
    }

    boolean isHealthy() {
        return healthy.get();
    }

    /** @return true if this call changed the state, so only real transitions get logged. */
    boolean setHealthy(boolean value) {
        return healthy.getAndSet(value) != value;
    }

    int inFlight() {
        return inFlight.get();
    }

    void requestStarted() {
        inFlight.incrementAndGet();
    }

    void requestFinished() {
        inFlight.decrementAndGet();
    }

    @Override
    public String toString() {
        return address;
    }
}
