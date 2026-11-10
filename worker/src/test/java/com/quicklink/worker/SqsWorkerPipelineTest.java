package com.quicklink.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.wait.strategy.Wait.forHttp;
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * The same pipeline, driven over SQS instead of RabbitMQ — and notably with no broker
 * container in sight.
 *
 * Two things are being proved. First, that app.queue=sqs really does swap the
 * transport: the Rabbit listener is not even instantiated, and the application starts
 * with no AMQP server anywhere. Second, that the SQS failure semantics work, which is
 * where the two brokers differ most: there is no ack, so a retry is simply a message
 * that was not deleted, and dead-lettering is the queue's redrive policy rather than
 * the application's dead-letter exchange.
 *
 * ElasticMQ stands in for SQS: ~130MB, speaks the SQS query API including redrive
 * policies, and needs no AWS account. LocalStack would also work and is heavier.
 */
@SpringBootTest(properties = "app.queue=sqs")
@Testcontainers
class SqsWorkerPipelineTest {

    private static final String ORIGINALS = "originals";
    private static final String PROCESSED = "processed";

    /** ElasticMQ's own account id in queue URLs. */
    private static final String ACCOUNT = "000000000000";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    static GenericContainer seaweedfs = new GenericContainer("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data")
            .withExposedPorts(8333)
            .waitingFor(forHttp("/").forPort(8333).forStatusCode(200));

    @Container
    static GenericContainer elasticmq = new GenericContainer("softwaremill/elasticmq-native:1.6.11")
            .withExposedPorts(9324)
            .waitingFor(forLogMessage(".*Started SQS rest server.*\\n", 1));

    private static String queueUrl;
    private static String dlqUrl;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String sqsEndpoint = "http://" + elasticmq.getHost() + ":" + elasticmq.getMappedPort(9324);

        // The queues exist before the application starts, because in the real
        // deployment Terraform creates them — the app never declares its own topology
        // the way it does with Rabbit.
        createQueues(sqsEndpoint);

        registry.add("app.s3.endpoint",
                () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333));
        registry.add("app.sqs.endpoint", () -> sqsEndpoint);
        registry.add("app.sqs.queue-url", () -> queueUrl);
        // Any non-blank pair: ElasticMQ ignores the values but the SDK still signs,
        // and leaving them blank would send the client down the default credentials
        // chain looking for an AWS account that does not exist here.
        registry.add("app.sqs.access-key", () -> "local");
        registry.add("app.sqs.secret-key", () -> "local-secret");
        registry.add("app.sqs.dlq-url", () -> dlqUrl);
        // Short poll: the test should not wait 20s for its last empty receive.
        registry.add("app.sqs.wait-seconds", () -> 1);
    }

    private static void createQueues(String endpoint) {
        try (SqsClient admin = client(endpoint)) {
            admin.createQueue(b -> b.queueName("media-compress-dlq"));
            // ElasticMQ reports its own configured address in the URL it returns, which
            // is not the mapped port, so the URL is composed from the endpoint instead.
            dlqUrl = endpoint + "/" + ACCOUNT + "/media-compress-dlq";
            String dlqArn = admin.getQueueAttributes(b -> b.queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes().get(QueueAttributeName.QUEUE_ARN);

            admin.createQueue(b -> b.queueName("media-compress").attributes(Map.of(
                    // The SQS equivalent of a dead-letter exchange: after this many
                    // receives the queue itself moves the message, with no cooperation
                    // from the consumer.
                    QueueAttributeName.REDRIVE_POLICY,
                    "{\"maxReceiveCount\":\"2\",\"deadLetterTargetArn\":\"" + dlqArn + "\"}",
                    // Deliberately tiny so a redelivery is observable in a test. In
                    // production this must exceed how long a compression can take.
                    QueueAttributeName.VISIBILITY_TIMEOUT, "1")));
            queueUrl = endpoint + "/" + ACCOUNT + "/media-compress";
        }
    }

    private static SqsClient client(String endpoint) {
        return SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("x", "x")))
                .build();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client s3;
    @Autowired SqsClient sqs;
    @Autowired ApplicationContext context;

    @BeforeEach
    void prepare() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:../app-service/src/main/resources/db/migration")
                .load()
                .migrate();
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

    /** A PENDING row whose object is really in the originals bucket. */
    private long stageAsset(byte[] content, String contentType) {
        String key = "obj-" + Instant.now().toEpochMilli() + "-" + content.length;
        s3.putObject(PutObjectRequest.builder()
                        .bucket(ORIGINALS).key(key).contentType(contentType)
                        .contentLength((long) content.length).build(),
                RequestBody.fromBytes(content));
        return insertPending(contentType, key, content.length);
    }

    private long insertPending(String contentType, String key, long bytes) {
        jdbc.update("""
                INSERT INTO media_asset (status, content_type, original_key, original_bytes)
                VALUES ('PENDING', ?, ?, ?)
                """, contentType, key, bytes);
        return jdbc.queryForObject("SELECT max(id) FROM media_asset", Long.class);
    }

    /** Exactly what SqsMediaJobQueue puts on the wire: plain JSON, no headers. */
    private void publishAsAppServiceWould(long assetId, String originalKey) {
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(
                "{\"assetId\":%d,\"originalKey\":\"%s\"}".formatted(assetId, originalKey)));
    }

    private Map<String, Object> awaitStatus(long assetId, String expected) {
        for (int i = 0; i < 60; i++) {
            Map<String, Object> row =
                    jdbc.queryForMap("SELECT * FROM media_asset WHERE id = ?", assetId);
            if (expected.equals(row.get("status"))) {
                return row;
            }
            sleep(500);
        }
        return jdbc.queryForMap("SELECT * FROM media_asset WHERE id = ?", assetId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String key(long assetId) {
        return jdbc.queryForObject("SELECT original_key FROM media_asset WHERE id=?",
                String.class, assetId);
    }

    private long inFlight(String url) {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(b -> b.queueUrl(url)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .attributes();
        return Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Long.parseLong(attributes.get(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    // -----------------------------------------------------------------

    @Test
    void theRabbitTransportIsNotWiredAtAll() {
        assertFalse(context.getBeanNamesForType(MediaCompressListener.class).length > 0,
                "app.queue=sqs must leave the AMQP listener uninstantiated, or the worker "
                        + "would try to consume from a broker that is not deployed");
        assertTrue(context.getBeanNamesForType(SqsQueuePoller.class).length >= 2,
                "both the job poller and the DLQ poller should be running");
    }

    @Test
    void takesAJobOverSqsAndMarksItReady() throws Exception {
        long assetId = stageAsset(png(600), "image/png");
        publishAsAppServiceWould(assetId, key(assetId));

        Map<String, Object> row = awaitStatus(assetId, "READY");
        assertEquals("READY", row.get("status"));
        assertNotNull(row.get("processed_key"));

        long storedBytes = s3.headObject(b -> b.bucket(PROCESSED)
                .key((String) row.get("processed_key"))).contentLength();
        assertEquals(((Number) row.get("processed_bytes")).longValue(), storedBytes);

        // Deleting the message IS the ack under SQS, so a successful job must leave the
        // queue empty — not merely invisible.
        assertEquals(0, inFlight(queueUrl), "a handled message must be deleted, not left to retry");
    }

    /**
     * The permanent-failure path: an undecodable file is marked FAILED and the message
     * is deleted immediately rather than being redelivered until the redrive policy
     * gives up on it.
     */
    @Test
    void anUndecodableFileEndsUpFailedAndIsNotRedelivered() {
        long assetId = stageAsset("this is not an image at all".getBytes(), "image/png");
        publishAsAppServiceWould(assetId, key(assetId));

        Map<String, Object> row = awaitStatus(assetId, "FAILED");
        assertEquals("FAILED", row.get("status"));
        assertNotNull(row.get("error_message"));
        assertEquals(0, inFlight(queueUrl));
        assertEquals(0, inFlight(dlqUrl), "a permanent failure needs no dead letter: it is "
                + "already recorded on the asset");
    }

    /**
     * The transient-failure path, which is where SQS looks least like AMQP: the handler
     * does nothing special, it just fails. The message reappears after the visibility
     * timeout, and after maxReceiveCount the queue moves it to the DLQ on its own — at
     * which point the DLQ poller records the give-up, as MediaDlqListener does on Rabbit.
     */
    @Test
    void aJobWhoseObjectIsMissingIsRedeliveredThenDeadLettered() {
        // A row with no object behind it: every attempt fails the same way, but it is
        // the kind of failure that could have been a transient S3 outage.
        long assetId = insertPending("image/png", "missing-object-key", 123);
        publishAsAppServiceWould(assetId, "missing-object-key");

        Map<String, Object> row = awaitStatus(assetId, "FAILED");
        assertEquals("FAILED", row.get("status"),
                "once the redrive policy moves it, the DLQ poller must record the failure "
                        + "instead of leaving the asset stuck in PROCESSING");
        assertEquals("processing failed after retries", row.get("error_message"));
    }
}
