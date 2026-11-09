package com.quicklink.app.media;

/** Raised inside the upload transaction; turned into a queue message only after it commits. */
record MediaUploadedEvent(Long assetId, String originalKey) {
}
