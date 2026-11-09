package com.quicklink.worker;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper.TypePrecedence;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerRabbitConfig {

    public static final String COMPRESS_QUEUE = "media.compress";
    public static final String DLQ = "media.compress.dlq";
    public static final String DLX = "media.compress.dlx";

    /**
     * Dead-lettering is what stops one unprocessable file from wedging the queue.
     * Without it, a message that always throws is requeued forever and blocks every
     * other job behind it.
     *
     * NOTE: queue arguments are immutable in RabbitMQ. Adding these to a queue that
     * already exists without them fails with PRECONDITION_FAILED — the old queue has
     * to be deleted first. This topology is mirrored in app-service's RabbitConfig;
     * a mismatch is a loud startup failure rather than a silent bug, which is why
     * duplicating it is tolerable.
     */
    @Bean
    Queue mediaCompressQueue() {
        return QueueBuilder.durable(COMPRESS_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    Queue mediaCompressDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    DirectExchange mediaCompressDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    Binding mediaCompressDlqBinding() {
        return BindingBuilder.bind(mediaCompressDlq()).to(mediaCompressDlx()).with(DLQ);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        // app-service stamps __TypeId__ = com.quicklink.app.media.MediaCompressJob, a
        // class this module does not have. INFERRED makes the listener's parameter type
        // win, so the services share only the JSON shape.
        converter.setTypePrecedence(TypePrecedence.INFERRED);
        return converter;
    }
}
