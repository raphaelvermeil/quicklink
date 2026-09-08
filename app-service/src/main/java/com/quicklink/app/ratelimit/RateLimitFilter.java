package com.quicklink.app.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ProxyManager<String> buckets;
    private final BucketConfiguration configuration;

    public RateLimitFilter(ProxyManager<String> buckets,
                           @Value("${app.rate-limit.capacity:10}") long capacity,
                           @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        this.buckets = buckets;
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofSeconds(windowSeconds))
                .build();
        this.configuration = BucketConfiguration.builder().addLimit(limit).build();
    }

    /** Only guard link creation — redirects must stay fast and unmetered. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                 && "/api/links".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String key = "rate:ip:" + request.getRemoteAddr();
        ConsumptionProbe probe = buckets.getProxy(key, () -> configuration)
                                        .tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = Math.max(1,
                Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.addHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Too Many Requests\"}");
    }
}
