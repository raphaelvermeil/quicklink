package com.quicklink.app.media;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The default. Serialisation is handled by the configured JacksonJsonMessageConverter. */
@Component
@ConditionalOnProperty(name = "app.queue", havingValue = "rabbit", matchIfMissing = true)
class RabbitMediaJobQueue implements MediaJobQueue {

    private final RabbitTemplate rabbitTemplate;

    RabbitMediaJobQueue(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(MediaCompressJob job) {
        rabbitTemplate.convertAndSend(RabbitConfig.COMPRESS_QUEUE, job);
    }
}
