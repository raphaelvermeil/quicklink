package com.quicklink.app.media;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class EmptyUploadException extends RuntimeException {

    public EmptyUploadException() {
        super("upload must contain a non-empty file");
    }
}
