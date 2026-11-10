package com.quicklink.app.media;

/**
 * Publishes a compression job. One method, because that is the entire contract the
 * application needs from a queue — which is what makes the broker swappable.
 *
 * Selected by `app.queue`: rabbit (default) or sqs.
 */
public interface MediaJobQueue {

    void publish(MediaCompressJob job);
}
