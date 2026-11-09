package com.quicklink.edge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * A reverse proxy: a server on one side, an HTTP client on the other.
 * Routes each request to a healthy backend chosen by the configured strategy.
 */
public final class EdgeLb {

    private static final int PORT = 8080;

    /**
     * Headers we must not copy to the backend. Two reasons they're here:
     * hop-by-hop headers describe a single TCP connection rather than the message,
     * and HttpClient throws IllegalArgumentException for the ones it manages itself.
     */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authenticate", "proxy-authorization",
            "host", "content-length", "date", "expect", "from", "via", "warning",
            "x-forwarded-for");   // we set this one ourselves, below

    /** Content-Length and Transfer-Encoding are decided by HttpServer when we reply. */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "content-length", "upgrade");

    public static void main(String[] args) throws IOException {
        List<Backend> backends = Arrays.stream(
                        System.getenv().getOrDefault("EDGE_BACKENDS", "localhost:8081").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Backend::new)
                .toList();

        BackendPool.Strategy strategy = BackendPool.Strategy.valueOf(
                System.getenv().getOrDefault("EDGE_STRATEGY", "ROUND_ROBIN")
                        .toUpperCase().replace('-', '_'));

        BackendPool pool = new BackendPool(backends, strategy);
        new HealthChecker(pool, Duration.ofSeconds(5), Duration.ofSeconds(2)).start();

        start(PORT, pool, proxyClient());
        System.out.println("edge-lb listening on http://localhost:" + PORT
                + " strategy=" + strategy + " backends=" + backends);
    }

    /** The HTTP client used for forwarding. Its two settings are both correctness choices. */
    static HttpClient proxyClient() {
        return HttpClient.newBuilder()
                // A proxy must NOT follow redirects: this app's whole purpose is
                // returning 302s, and following them would swallow the Location header.
                .followRedirects(HttpClient.Redirect.NEVER)
                // Without this, HttpClient adds Connection/Upgrade/HTTP2-Settings headers
                // to every request trying to negotiate h2c. The backends speak HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Starts the proxy and returns the running server. Pass port 0 to let the OS pick a
     * free port — that is what lets tests run several instances without colliding.
     */
    static HttpServer start(int port, BackendPool pool, HttpClient client) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // One virtual thread per request: a proxy is almost pure I/O waiting.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> proxy(exchange, client, pool));
        server.start();
        return server;
    }

    /**
     * Health checks alone are not enough for failover: between an instance dying and the
     * next probe noticing, round-robin keeps sending it traffic and that share of requests
     * fails. So a failed forward marks the backend unhealthy immediately and retries on
     * another one. The periodic checker then handles putting it back when it recovers.
     */
    private static void proxy(HttpExchange exchange, HttpClient client, BackendPool pool) {
        try (exchange) {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String forwardedFor = forwardedFor(exchange);
            int attempts = pool.all().size();

            for (int attempt = 0; attempt < attempts; attempt++) {
                Backend backend = pool.next();
                if (backend == null) {
                    sendStatus(exchange, 503);   // every backend is down
                    return;
                }

                HttpResponse<byte[]> response;
                backend.requestStarted();
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + backend.address() + exchange.getRequestURI()))
                            .method(exchange.getRequestMethod(),
                                    requestBody.length == 0
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(requestBody));

                    copyRequestHeaders(exchange, builder);
                    builder.header("X-Forwarded-For", forwardedFor);

                    response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // This backend is the problem, not the request. Take it out now
                    // rather than waiting up to a full health-check interval.
                    if (backend.setHealthy(false)) {
                        System.out.println("[proxy] " + backend + " failed a request, marking UNHEALTHY");
                    }
                    continue;
                } finally {
                    backend.requestFinished();
                }

                // We have a response. Anything that fails from here is the client
                // hanging up, which is not a reason to retry or blame the backend.
                writeResponse(exchange, response);
                return;
            }

            sendStatus(exchange, 502);   // every attempt failed

        } catch (IOException e) {
            sendStatus(exchange, 502);
        }
    }

    private static void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (SKIP_REQUEST_HEADERS.contains(header.getKey().toLowerCase())) {
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
    }

    /** Appends to any existing chain, per the X-Forwarded-For convention. */
    private static String forwardedFor(HttpExchange exchange) {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        String existing = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        return existing == null ? clientIp : existing + ", " + clientIp;
    }

    private static void writeResponse(HttpExchange exchange, HttpResponse<byte[]> response)
            throws IOException {
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if (SKIP_RESPONSE_HEADERS.contains(header.getKey().toLowerCase())) {
                continue;
            }
            for (String value : header.getValue()) {
                exchange.getResponseHeaders().add(header.getKey(), value);
            }
        }

        byte[] body = response.body();
        // -1 means "no response body"; a 0 here would switch HttpServer to chunked encoding.
        exchange.sendResponseHeaders(response.statusCode(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) {
        try (exchange) {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // the client is already gone; nothing useful left to do
        }
    }
}
