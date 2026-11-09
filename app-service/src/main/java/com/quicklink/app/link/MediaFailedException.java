package com.quicklink.app.link;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 410 Gone, not 202 and not 404: the link is real and the upload happened, but
 * processing failed permanently. Answering 202 would tell the client to keep polling
 * for something that will never arrive.
 */
@ResponseStatus(HttpStatus.GONE)
public class MediaFailedException extends RuntimeException {

    public MediaFailedException(String shortCode) {
        super("media for " + shortCode + " could not be processed");
    }
}
