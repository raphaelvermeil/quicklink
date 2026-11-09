package com.quicklink.worker;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
class MediaCompressListener {

    private static final Logger log = LoggerFactory.getLogger(MediaCompressListener.class);

    private final S3Client s3;
    private final MediaAssetStore assets;
    private final ImageCompressor compressor;
    private final String originalsBucket;
    private final String processedBucket;
    private final String instanceId = System.getenv().getOrDefault("HOSTNAME", "local");

    MediaCompressListener(S3Client s3,
                          MediaAssetStore assets,
                          ImageCompressor compressor,
                          @Value("${app.s3.buckets.originals}") String originalsBucket,
                          @Value("${app.s3.buckets.processed}") String processedBucket) {
        this.s3 = s3;
        this.assets = assets;
        this.compressor = compressor;
        this.originalsBucket = originalsBucket;
        this.processedBucket = processedBucket;
    }

    @RabbitListener(queues = WorkerRabbitConfig.COMPRESS_QUEUE)
    void onJob(MediaCompressJob job) throws Exception {
        Optional<String> status = assets.findStatus(job.assetId());

        // A message can reference an asset that does not exist: app-service publishes
        // after commit, but a replayed or hand-injected message has no such guarantee.
        // Nothing to retry, so ack and drop rather than loop.
        if (status.isEmpty()) {
            log.warn("[{}] asset {} does not exist, dropping job", instanceId, job.assetId());
            return;
        }

        // Idempotency. RabbitMQ guarantees at-least-once delivery, so a duplicate is
        // normal operation, not a bug. Re-compressing would burn CPU and orphan the
        // previously stored object.
        if ("READY".equals(status.get())) {
            log.info("[{}] asset {} already READY, skipping", instanceId, job.assetId());
            return;
        }

        assets.markProcessing(job.assetId());

        // Logged per attempt, so retries are visible rather than silent.
        log.info("[{}] compressing asset {} from {}", instanceId, job.assetId(), job.originalKey());

        try {
            compressAndStore(job);
        } catch (UnsupportedMediaException e) {
            // Permanent: this file will never succeed, so skip the retries entirely
            // and dead-letter it immediately.
            log.warn("[{}] asset {} permanently unprocessable: {}", instanceId, job.assetId(),
                    e.getMessage());
            assets.markFailed(job.assetId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }

    private void compressAndStore(MediaCompressJob job) throws Exception {
        // Loaded fully into memory: Thumbnailator needs a BufferedImage anyway, and
        // spring.servlet.multipart.max-file-size caps uploads at 25MB.
        ResponseBytes<GetObjectResponse> original =
                s3.getObjectAsBytes(b -> b.bucket(originalsBucket).key(job.originalKey()));
        String contentType = original.response().contentType();

        byte[] compressed = compressor.compress(original.asByteArray(), contentType);

        String processedKey = UUID.randomUUID().toString();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(processedBucket)
                        .key(processedKey)
                        .contentType(contentType)
                        .contentLength((long) compressed.length)
                        .build(),
                RequestBody.fromBytes(compressed));

        assets.markReady(job.assetId(), processedKey, compressed.length);
        log.info("[{}] asset {} READY: {} -> {} bytes", instanceId, job.assetId(),
                original.asByteArray().length, compressed.length);
    }
}
