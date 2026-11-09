package com.quicklink.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quicklink.edge.BackendPool.Strategy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The load-balancing decision is pure logic — no sockets, no servers — so it is
 * unit-testable on its own. These are the tests that would catch a selection bug
 * before it turned into "one instance gets all the traffic" in the demo.
 */
class BackendPoolTest {

    private static Backend b(String address) {
        return new Backend(address);
    }

    @Test
    void roundRobinVisitsEveryBackendInOrder() {
        BackendPool pool = new BackendPool(List.of(b("a"), b("b"), b("c")), Strategy.ROUND_ROBIN);

        List<String> picks = IntStream.range(0, 6)
                .mapToObj(i -> pool.next().address())
                .toList();

        assertEquals(List.of("a", "b", "c", "a", "b", "c"), picks);
    }

    @Test
    void roundRobinSkipsUnhealthyBackends() {
        Backend a = b("a");
        Backend down = b("down");
        Backend c = b("c");
        down.setHealthy(false);

        BackendPool pool = new BackendPool(List.of(a, down, c), Strategy.ROUND_ROBIN);

        Set<String> picks = IntStream.range(0, 20)
                .mapToObj(i -> pool.next().address())
                .collect(Collectors.toSet());

        assertEquals(Set.of("a", "c"), picks, "an unhealthy backend must never be selected");
    }

    @Test
    void returnsNullWhenEveryBackendIsDown() {
        Backend a = b("a");
        Backend c = b("c");
        a.setHealthy(false);
        c.setHealthy(false);

        BackendPool pool = new BackendPool(List.of(a, c), Strategy.ROUND_ROBIN);

        assertNull(pool.next(), "caller needs null so it can answer 503");
    }

    @Test
    void recoveredBackendRejoinsRotation() {
        Backend a = b("a");
        Backend flaky = b("flaky");
        flaky.setHealthy(false);
        BackendPool pool = new BackendPool(List.of(a, flaky), Strategy.ROUND_ROBIN);

        IntStream.range(0, 5).forEach(i -> assertEquals("a", pool.next().address()));

        flaky.setHealthy(true);

        Set<String> picks = IntStream.range(0, 10)
                .mapToObj(i -> pool.next().address())
                .collect(Collectors.toSet());
        assertTrue(picks.contains("flaky"), "a backend that passes health checks again must be used");
    }

    @Test
    void leastConnectionsPicksTheIdlestBackend() {
        Backend busy = b("busy");
        Backend idle = b("idle");
        busy.requestStarted();
        busy.requestStarted();

        BackendPool pool = new BackendPool(List.of(busy, idle), Strategy.LEAST_CONNECTIONS);

        assertEquals("idle", pool.next().address());
    }

    @Test
    void leastConnectionsIgnoresUnhealthyBackendsEvenWhenIdle() {
        Backend idleButDown = b("idle-down");
        Backend busyButUp = b("busy-up");
        idleButDown.setHealthy(false);
        busyButUp.requestStarted();

        BackendPool pool = new BackendPool(List.of(idleButDown, busyButUp), Strategy.LEAST_CONNECTIONS);

        assertEquals("busy-up", pool.next().address(),
                "health must be checked before connection count");
    }

    @Test
    void setHealthyReportsOnlyRealTransitions() {
        Backend a = b("a");

        assertTrue(a.setHealthy(false), "healthy -> unhealthy is a transition");
        assertTrue(!a.setHealthy(false), "unhealthy -> unhealthy is not");
        assertTrue(a.setHealthy(true), "unhealthy -> healthy is a transition");
    }
}
