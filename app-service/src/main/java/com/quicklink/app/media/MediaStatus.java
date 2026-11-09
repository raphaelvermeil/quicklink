package com.quicklink.app.media;

/**
 * PENDING -> PROCESSING -> READY
 *                \------> FAILED   (retries exhausted)
 */
public enum MediaStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
