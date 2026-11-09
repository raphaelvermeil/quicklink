package com.quicklink.edge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls every backend on a fixed interval and flips its healthy flag.
 *
 * It polls /actuator/health/readiness rather than /actuator/health: readiness answers
 * "can this instance take traffic?", while the aggregate also folds in Postgres and
 * Redis. A Redis blip would otherwise pull a perfectly serviceable instance out of
 * rotation, even though cache-aside degrades to Postgres and keeps working.
 */
final class HealthChecker {

    private static final String PROBE_PATH = "/actuator/health/readiness";

    private final BackendPool pool;
    private final HttpClient client;
    private final Duration interval;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "health-checker");
                t.setDaemon(true);
                return t;
            });

    HealthChecker(BackendPool pool, Duration interval, Duration timeout) {
        this.pool = pool;
        this.interval = interval;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    void start() {
        scheduler.scheduleAtFixedRate(this::checkAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkAll() {
        for (Backend backend : pool.all()) {
            boolean up = probe(backend);
            if (backend.setHealthy(up)) {
                System.out.println("[health] " + backend + " is now " + (up ? "HEALTHY" : "UNHEALTHY"));
            }
        }
    }

    private boolean probe(Backend backend) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + backend.address() + PROBE_PATH))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Connection refused, timeout, DNS failure — all mean "not taking traffic".
            return false;
        }
    }
}
