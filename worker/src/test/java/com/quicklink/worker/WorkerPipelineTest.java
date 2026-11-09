package com.quicklink.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.wait.strategy.Wait.forHttp;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The whole worker side of the pipeline against real infrastructure: Postgres,
 * RabbitMQ and an S3-compatible store, all disposable.
 *
 * The point of going through the broker rather than calling the listener directly is
 * the cross-module message contract: app-service stamps __TypeId__ with a class this
 * module does not have, and only a real round trip proves TypePrecedence.INFERRED works.
 */
@SpringBootTest
@Testcontainers
class WorkerPipelineTest {

    private static final String ORIGINALS = "originals";
    private static final String PROCESSED = "processed";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    /** No Testcontainers module for SeaweedFS, so a GenericContainer does the job. */
    @Container
    static GenericContainer seaweedfs = new GenericContainer("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data")
            .withExposedPorts(8333)
            .waitingFor(forHttp("/").forPort(8333).forStatusCode(200));

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.s3.endpoint",
                () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired S3Client s3;

    @BeforeEach
    void prepare() {
        // Apply app-service's REAL migrations rather than a copied schema, so this test
        // cannot pass against a table shape that production does not have.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:../app-service/src/main/resources/db/migration")
                .load()
                .migrate();

        // The containers are static and shared by every test in this class, so this
        // runs repeatedly. Creating an existing bucket answers 409
        // BucketAlreadyOwnedByYou — the same idempotency trap as s3-init in compose.
        createBucketIfAbsent(ORIGINALS);
        createBucketIfAbsent(PROCESSED);
    }

    private void createBucketIfAbsent(String bucket) {
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyThere) {
            // fine: a previous test in this class created it
        }
    }

    private static byte[] png(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, (((x * 7) % 256) << 16) | (((y * 5) % 256) << 8) | ((x + y) % 256));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Stores an object and a PENDING row, exactly as app-service's upload would. */
    private long stageAsset(byte[] content, String contentType) {
        String key = "obj-" + Instant.now().toEpochMilli() + "-" + content.length;
        s3.putObject(PutObjectRequest.builder()
                        .bucket(ORIGINALS).key(key).contentType(contentType)
                        .contentLength((long) content.length).build(),
                RequestBody.fromBytes(content));

        jdbc.update("""
                INSERT INTO media_asset (status, content_type, original_key, original_bytes)
                VALUES ('PENDING', ?, ?, ?)
                """, contentType, key, content.length);
        Long id = jdbc.queryForObject("SELECT max(id) FROM media_asset", Long.class);
        return id;
    }

    /** Publishes in app-service's wire format: JSON plus a foreign __TypeId__ header. */
    private void publishAsAppServiceWould(long assetId, String originalKey) {
        String payload = "{\"assetId\":%d,\"originalKey\":\"%s\"}".formatted(assetId, originalKey);
        rabbitTemplate.send(WorkerRabbitConfig.COMPRESS_QUEUE,
                org.springframework.amqp.core.MessageBuilder
                        .withBody(payload.getBytes())
                        .andProperties(org.springframework.amqp.core.MessagePropertiesBuilder
                                .newInstance()
                                .setContentType("application/json")
                                .setHeader("__TypeId__", "com.quicklink.app.media.MediaCompressJob")
                                .build())
                        .build());
    }

    private Map<String, Object> awaitStatus(long assetId, String expected) {
        for (int i = 0; i < 60; i++) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM media_asset WHERE id = ?", assetId);
            if (expected.equals(row.get("status"))) {
                return row;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return jdbc.queryForMap("SELECT * FROM media_asset WHERE id = ?", assetId);
    }

    // -----------------------------------------------------------------

    @Test
    void takesAJobFromTheQueueAndMarksItReady() throws Exception {
        byte[] content = png(600);
        long assetId = stageAsset(content, "image/png");
        String key = jdbc.queryForObject("SELECT original_key FROM media_asset WHERE id=?",
                String.class, assetId);

        publishAsAppServiceWould(assetId, key);

        Map<String, Object> row = awaitStatus(assetId, "READY");
        assertEquals("READY", row.get("status"),
                "a message carrying a foreign __TypeId__ must still deserialize");
        assertNotNull(row.get("processed_key"));
        assertTrue(((Number) row.get("processed_bytes")).longValue() > 0);

        long storedBytes = s3.headObject(b -> b.bucket(PROCESSED)
                .key((String) row.get("processed_key"))).contentLength();
        assertEquals(((Number) row.get("processed_bytes")).longValue(), storedBytes,
                "recorded size must match what is actually in the processed bucket");
    }

    @Test
    void aDuplicateDeliveryIsIgnored() throws Exception {
        long assetId = stageAsset(png(400), "image/png");
        String key = jdbc.queryForObject("SELECT original_key FROM media_asset WHERE id=?",
                String.class, assetId);

        publishAsAppServiceWould(assetId, key);
        Map<String, Object> first = awaitStatus(assetId, "READY");
        String firstProcessedKey = (String) first.get("processed_key");

        publishAsAppServiceWould(assetId, key);   // at-least-once delivery is normal
        Thread.sleep(2000);

        String afterKey = jdbc.queryForObject("SELECT processed_key FROM media_asset WHERE id=?",
                String.class, assetId);
        assertEquals(firstProcessedKey, afterKey,
                "re-processing would orphan the first object and waste the work");
    }

    /** A file that can never be decoded must end up FAILED, not retried forever. */
    @Test
    void anUndecodableFileEndsUpFailed() throws Exception {
        long assetId = stageAsset("this is not an image at all".getBytes(), "image/png");
        String key = jdbc.queryForObject("SELECT original_key FROM media_asset WHERE id=?",
                String.class, assetId);

        publishAsAppServiceWould(assetId, key);

        Map<String, Object> row = awaitStatus(assetId, "FAILED");
        assertEquals("FAILED", row.get("status"));
        assertNotNull(row.get("error_message"));
    }
}
