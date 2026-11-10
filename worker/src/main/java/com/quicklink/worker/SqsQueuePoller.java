package com.quicklink.worker;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * The SQS transport: a long-polling loop, which is what SQS gives you in place of
 * Rabbit's push delivery.
 *
 * Three things are worth noticing, because they are exactly what differs from AMQP:
 *
 *  - There is no ack. ReceiveMessage only makes a message invisible to other
 *    consumers for the queue's visibility timeout; DeleteMessage is the ack. Simply
 *    failing to delete is how a retry happens.
 *  - Retries and dead-lettering are the queue's job, not the client's. The redrive
 *    policy moves a message to the DLQ after maxReceiveCount receives, so there is no
 *    retry/backoff configuration here to mirror the Rabbit listener's.
 *  - The visibility timeout is therefore a real deadline: work that outlives it gets
 *    delivered to a second consumer. MediaCompressHandler's READY check is what keeps
 *    that from doing damage.
 *
 * One virtual thread per queue. A platform thread parked in a 20-second poll would be
 * a waste of a megabyte of stack; a virtual thread parked in it costs almost nothing.
 */
class SqsQueuePoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsQueuePoller.class);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

    /** Like Consumer, but allowed to fail — which is the whole point of a retry. */
    interface JobHandler {
        void handle(MediaCompressJob job) throws Exception;
    }

    private final String name;
    private final String queueUrl;
    private final SqsClient sqs;
    private final ObjectMapper json;
    private final int waitSeconds;
    private final JobHandler handler;

    private volatile boolean running;
    private Thread thread;

    SqsQueuePoller(String name, String queueUrl, SqsClient sqs, ObjectMapper json,
                   int waitSeconds, JobHandler handler) {
        this.name = name;
        this.queueUrl = queueUrl;
        this.sqs = sqs;
        this.json = json;
        this.waitSeconds = waitSeconds;
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("sqs-poller-" + name).start(this::loop);
        log.info("[sqs:{}] polling {}", name, queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(waitSeconds)).messages();
                for (Message message : messages) {
                    if (!running) {
                        return;
                    }
                    process(message);
                }
            } catch (Exception e) {
                if (!running) {
                    return;    // interrupted mid-poll by stop(); not an error
                }
                // Queue unreachable, credentials rejected, and so on. Backing off keeps
                // a broken dependency from turning into a hot loop against the API.
                log.warn("[sqs:{}] receive failed, backing off: {}", name, e.toString());
                sleepQuietly();
            }
        }
    }

    private void process(Message message) {
        MediaCompressJob job;
        try {
            job = json.readValue(message.body(), MediaCompressJob.class);
        } catch (Exception e) {
            // A body that will never parse would be redelivered until the redrive
            // policy moved it, which is a slow way to achieve nothing. Drop it.
            log.error("[sqs:{}] unparseable message dropped: {}", name, message.body(), e);
            delete(message);
            return;
        }

        try {
            handler.handle(job);
            delete(message);
        } catch (UnsupportedMediaException e) {
            // Permanent. The asset is already FAILED, so redelivering until the
            // redrive policy fires would only delay the inevitable.
            delete(message);
        } catch (Exception e) {
            // Transient: leave it undeleted. It becomes visible again after the
            // visibility timeout, and reaches the DLQ once maxReceiveCount is hit.
            log.warn("[sqs:{}] asset {} failed, leaving for redelivery: {}", name,
                    job.assetId(), e.toString());
        }
    }

    private void delete(Message message) {
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(ERROR_BACKOFF);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
