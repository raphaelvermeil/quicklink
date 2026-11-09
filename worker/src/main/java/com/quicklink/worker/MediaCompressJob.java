package com.quicklink.worker;

/**
 * The worker's own copy of the message shape. Deliberately NOT shared with app-service:
 * two services integrating over a queue should be coupled by the JSON contract, not by
 * a Java class. WorkerRabbitConfig tells the converter to trust this method parameter
 * type instead of the producer's __TypeId__ header.
 */
public record MediaCompressJob(Long assetId, String originalKey) {
}
