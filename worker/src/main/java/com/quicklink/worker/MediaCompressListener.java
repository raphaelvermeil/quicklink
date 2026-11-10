package com.quicklink.worker;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The RabbitMQ transport. All it does is deliver the job and translate a permanent
 * failure into Rabbit's vocabulary; the work itself lives in MediaCompressHandler.
 */
@Component
@ConditionalOnProperty(name = "app.queue", havingValue = "rabbit", matchIfMissing = true)
class MediaCompressListener {

    private final MediaCompressHandler handler;

    MediaCompressListener(MediaCompressHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = WorkerRabbitConfig.COMPRESS_QUEUE)
    void onJob(MediaCompressJob job) throws Exception {
        try {
            handler.handle(job);
        } catch (UnsupportedMediaException e) {
            // Permanent: skip the configured retries entirely and dead-letter it now.
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }
}
