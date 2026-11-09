package com.quicklink.app.link;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The link exists and is valid, but the file behind it is still being compressed.
 * 202 rather than 404: the client should come back, not give up.
 */
@ResponseStatus(HttpStatus.ACCEPTED)
public class MediaNotReadyException extends RuntimeException {

    public MediaNotReadyException(String shortCode) {
        super("media for " + shortCode + " is still being processed");
    }
}
