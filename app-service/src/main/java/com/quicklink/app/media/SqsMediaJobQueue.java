package com.quicklink.app.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The same job, on SQS instead of RabbitMQ.
 *
 * Note what is NOT here: no exchange, no routing key, no queue declaration. SQS has no
 * exchange concept — you send to a queue URL — and the queue itself is created by
 * Terraform rather than by the application at startup. The dead-letter behaviour moves
 * with it: Rabbit needs a dead-letter exchange plus `default-requeue-rejected: false`,
 * whereas SQS does it with a redrive policy and maxReceiveCount on the queue.
 */
@Component
@ConditionalOnProperty(name = "app.queue", havingValue = "sqs")
class SqsMediaJobQueue implements MediaJobQueue {

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final String queueUrl;

    SqsMediaJobQueue(SqsClient sqs, ObjectMapper json, SqsProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.queueUrl = properties.queueUrl();
    }

    @Override
    public void publish(MediaCompressJob job) {
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(json.writeValueAsString(job)));
    }
}
