package com.quicklink.app.media;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.queue", havingValue = "rabbit", matchIfMissing = true)
public class RabbitConfig {

    public static final String COMPRESS_QUEUE = "media.compress";
    public static final String DLQ = "media.compress.dlq";
    public static final String DLX = "media.compress.dlx";

    /**
     * Durable: jobs must survive a broker restart, or uploads would be stranded PENDING.
     *
     * The dead-letter arguments MUST match the worker's declaration exactly — RabbitMQ
     * treats queue arguments as immutable and rejects a conflicting redeclaration with
     * PRECONDITION_FAILED. That makes the duplication self-policing: a drift between
     * the two services is a loud startup failure, not a silent bug.
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

    /**
     * JacksonJsonMessageConverter, not Jackson2JsonMessageConverter: Boot 4 ships
     * Jackson 3, and spring-amqp provides one class per Jackson generation.
     * Without this the default converter serialises with Java serialization.
     */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
