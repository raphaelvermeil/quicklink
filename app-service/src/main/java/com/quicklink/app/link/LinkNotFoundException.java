package com.quicklink.app.link;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String shortCode) {
        super("No link for code: " + shortCode);
    }
}
