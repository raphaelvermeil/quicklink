package com.quicklink.app.media;

/** The queue message. Deliberately tiny: the worker re-reads state from Postgres and S3. */
public record MediaCompressJob(Long assetId, String originalKey) {
}
