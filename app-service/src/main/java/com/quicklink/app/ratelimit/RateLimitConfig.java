package com.quicklink.app.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    /**
     * Uses DataRedisConnectionDetails, NOT the spring.data.redis.* properties.
     * @ServiceConnection (Testcontainers) supplies a ConnectionDetails bean and
     * leaves the properties untouched, so reading the properties directly would
     * silently talk to the developer's Redis instead of the test container.
     */
    @Bean(destroyMethod = "shutdown")
    RedisClient bucket4jRedisClient(DataRedisConnectionDetails connectionDetails) {
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        return RedisClient.create(RedisURI.create(standalone.getHost(), standalone.getPort()));
    }

    @Bean
    ProxyManager<String> rateLimitBuckets(RedisClient client) {
        return Bucket4jLettuce.casBasedBuilder(client)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(10)))
                .build()
                .withMapper(key -> key.getBytes(StandardCharsets.UTF_8));
    }
}
