package com.quicklink.app;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import com.redis.testcontainers.RedisContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * Every datastore the application touches, containerised and shared.
 *
 * Extend this instead of pointing a test at the dev compose stack. Tests that used the
 * developer's Redis were writing state other tests read, and — worse — they created
 * links inside rolled-back transactions, leaving click counters in Redis for rows that
 * never existed. One of those reaching the flusher's dirty set would violate
 * link_stats' foreign key and, because the flush was transactional over the whole
 * batch, destroy every other link's pending clicks.
 *
 * The containers are static, so they start once per JVM and are reused by every
 * subclass. Spring also caches the context, so subclasses that add no extra
 * configuration share one application context too.
 */
@SpringBootTest
public abstract class IntegrationTest {

    // The singleton-container pattern, NOT @Testcontainers/@Container. That extension
    // stops a static container when its test CLASS finishes, so the first subclass to
    // complete would tear the containers down for every subclass after it — which
    // showed up as "Connection refused" on an already-mapped port. Started once here
    // and never stopped; Testcontainers' Ryuk sidecar removes them when the JVM exits.

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @ServiceConnection
    static final RedisContainer redis = new RedisContainer("redis:7");

    /** Needed even by tests that never send a message: the admin declares queues at startup. */
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    /** No Testcontainers module for SeaweedFS, so a GenericContainer does the job. */
    static final GenericContainer seaweedfs = new GenericContainer("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(200));

    static {
        // In parallel: four sequential starts is about 15s, together about 6s.
        Startables.deepStart(postgres, redis, rabbit, seaweedfs).join();
    }

    @Autowired
    private S3Client s3ForBuckets;

    @Value("${app.s3.buckets.originals}")
    private String originalsBucket;

    @Value("${app.s3.buckets.processed}")
    private String processedBucket;

    /**
     * In compose the s3-init container creates these. Nothing does so here, and a
     * missing bucket surfaces as 403 Access Denied from SeaweedFS rather than 404,
     * which is a misleading error to debug. Idempotent because the container is
     * shared by every test.
     */
    @BeforeEach
    void createBuckets() {
        for (String bucket : new String[] { originalsBucket, processedBucket }) {
            try {
                s3ForBuckets.createBucket(b -> b.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyThere) {
                // another test in this JVM already created it
            }
        }
    }

    @DynamicPropertySource
    static void objectStoreProperties(DynamicPropertyRegistry registry) {
        String base = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
        registry.add("app.s3.endpoint", () -> base);
        // The presigner signs for the host it is given, so tests must presign against
        // the same address they will fetch from.
        registry.add("app.s3.public-endpoint", () -> base);
    }
}
