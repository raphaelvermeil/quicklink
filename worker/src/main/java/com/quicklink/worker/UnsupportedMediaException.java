package com.quicklink.worker;

/** A file this worker can never process. Retrying it would be pointless. */
class UnsupportedMediaException extends RuntimeException {

    UnsupportedMediaException(String message) {
        super(message);
    }
}
