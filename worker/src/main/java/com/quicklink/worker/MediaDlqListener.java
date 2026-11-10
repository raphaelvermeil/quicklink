package com.quicklink.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Anything that reaches the dead-letter queue has exhausted its retries (or was
 * rejected outright). Recording FAILED here means a stuck upload is visible in the
 * API rather than silently stranded in PROCESSING forever.
 */
@Component
@ConditionalOnProperty(name = "app.queue", havingValue = "rabbit", matchIfMissing = true)
class MediaDlqListener {

    private static final Logger log = LoggerFactory.getLogger(MediaDlqListener.class);

    private final MediaAssetStore assets;

    MediaDlqListener(MediaAssetStore assets) {
        this.assets = assets;
    }

    @RabbitListener(queues = WorkerRabbitConfig.DLQ)
    void onDeadLetter(MediaCompressJob job) {
        log.error("[dlq] asset {} gave up after retries (key {})", job.assetId(), job.originalKey());
        assets.markFailedIfNotReady(job.assetId(), "processing failed after retries");
    }
}
