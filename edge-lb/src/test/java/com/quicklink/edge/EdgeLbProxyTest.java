package com.quicklink.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the proxy, using stub backends started inside the test rather
 * than real app containers. That keeps them fast and lets a test kill a backend
 * mid-run, which is the behaviour hardest to verify by hand.
 */
class EdgeLbProxyTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @AfterEach
    void stopAll() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    /** A stub backend that echoes its own name, and records the headers it was sent. */
    private Stub stub(String name) throws IOException {
        Stub s = new Stub(name);
        servers.add(s.server);
        return s;
    }

    private static final class Stub {
        final String name;
        final HttpServer server;
        final ConcurrentLinkedQueue<String> seenForwardedFor = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> seenBodies = new ConcurrentLinkedQueue<>();

        Stub(String name) throws IOException {
            this.name = name;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                seenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
                if (xff != null) {
                    seenForwardedFor.add(xff);
                }
                exchange.getResponseHeaders().add("X-Stub-Name", name);

                if (exchange.getRequestURI().getPath().startsWith("/redirect")) {
                    exchange.getResponseHeaders().add("Location", "https://example.com/final");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                byte[] body = ("served-by:" + name).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        String address() {
            return "localhost:" + server.getAddress().getPort();
        }
    }

    private int startProxy(BackendPool pool) throws IOException {
        HttpServer proxy = EdgeLb.start(0, pool, client);
        servers.add(proxy);
        return proxy.getAddress().getPort();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------

    @Test
    void forwardsRequestAndReturnsTheBackendResponse() throws Exception {
        Stub a = stub("a");
        int port = startProxy(new BackendPool(List.of(new Backend(a.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        HttpResponse<String> response = get(port, "/anything");

        assertEquals(200, response.statusCode());
        assertEquals("served-by:a", response.body());
        assertEquals("a", response.headers().firstValue("X-Stub-Name").orElseThrow(),
                "backend response headers must be copied back");
    }

    @Test
    void roundRobinsAcrossBackends() throws Exception {
        Stub a = stub("a");
        Stub b = stub("b");
        int port = startProxy(new BackendPool(
                List.of(new Backend(a.address()), new Backend(b.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        List<String> served = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            served.add(get(port, "/").body());
        }

        assertEquals(List.of("served-by:a", "served-by:b", "served-by:a",
                "served-by:b", "served-by:a", "served-by:b"), served);
    }

    /** The behaviour the whole app depends on: a 302 must pass through, not be followed. */
    @Test
    void passesRedirectsThroughWithoutFollowingThem() throws Exception {
        Stub a = stub("a");
        int port = startProxy(new BackendPool(List.of(new Backend(a.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        HttpResponse<String> response = get(port, "/redirect/abc");

        assertEquals(302, response.statusCode());
        assertEquals("https://example.com/final",
                response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void forwardsTheRequestBody() throws Exception {
        Stub a = stub("a");
        int port = startProxy(new BackendPool(List.of(new Backend(a.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/links"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"https://x.test\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals("{\"longUrl\":\"https://x.test\"}", a.seenBodies.poll());
    }

    @Test
    void addsXForwardedForForTheBackend() throws Exception {
        Stub a = stub("a");
        int port = startProxy(new BackendPool(List.of(new Backend(a.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        get(port, "/");

        String xff = a.seenForwardedFor.poll();
        assertTrue(xff != null && !xff.isBlank(),
                "the backend needs X-Forwarded-For to recover the real client IP");
    }

    @Test
    void appendsToAnExistingForwardedForChain() throws Exception {
        Stub a = stub("a");
        int port = startProxy(new BackendPool(List.of(new Backend(a.address())),
                BackendPool.Strategy.ROUND_ROBIN));

        client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/"))
                        .header("X-Forwarded-For", "203.0.113.9")
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        String xff = a.seenForwardedFor.poll();
        assertTrue(xff != null && xff.startsWith("203.0.113.9, "),
                "existing chain must be preserved and appended to, got: " + xff);
    }

    /**
     * The gap health checks alone cannot close: a backend that dies between probes.
     * Without retry-on-failure this test fails roughly half the time.
     */
    @Test
    void retriesOnAnotherBackendWhenOneIsDead() throws Exception {
        Stub alive = stub("alive");
        Stub doomed = stub("doomed");
        Backend aliveBackend = new Backend(alive.address());
        Backend deadBackend = new Backend(doomed.address());

        int port = startProxy(new BackendPool(List.of(deadBackend, aliveBackend),
                BackendPool.Strategy.ROUND_ROBIN));

        doomed.server.stop(0);   // kill it WITHOUT telling the pool

        Set<Integer> statuses = IntStream.range(0, 10)
                .mapToObj(i -> {
                    try {
                        return get(port, "/").statusCode();
                    } catch (Exception e) {
                        return -1;
                    }
                })
                .collect(Collectors.toSet());

        assertEquals(Set.of(200), statuses,
                "every request must still succeed by retrying on the surviving backend");
    }

    @Test
    void returns503WhenEveryBackendIsDead() throws Exception {
        Stub a = stub("a");
        Backend backend = new Backend(a.address());
        a.server.stop(0);
        backend.setHealthy(false);

        int port = startProxy(new BackendPool(List.of(backend), BackendPool.Strategy.ROUND_ROBIN));

        assertEquals(503, get(port, "/").statusCode());
    }
}
