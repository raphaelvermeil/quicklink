package com.quicklink.app.media;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes the compression job only AFTER the upload transaction commits.
 *
 * Writing to Postgres and to RabbitMQ are two separate systems with no shared
 * transaction. Publishing inside the transaction risks the worst ordering: the message
 * is delivered, the transaction then rolls back, and the worker is handed an asset id
 * that does not exist. Publishing after commit means the only remaining failure is a
 * lost message, which leaves the asset PENDING — visible, and safe to re-drive.
 */
@Component
class MediaJobPublisher {

    private final RabbitTemplate rabbitTemplate;

    MediaJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onUploaded(MediaUploadedEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.COMPRESS_QUEUE,
                new MediaCompressJob(event.assetId(), event.originalKey()));
    }
}
