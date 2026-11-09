package com.quicklink.app.media;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * 202 Accepted, not 201 Created: the short link exists, but the thing it will serve
     * is not ready yet. The heavy work happens on a worker, off the request thread.
     */
    @PostMapping("/api/media")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MediaUploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return mediaService.upload(file);
    }

    /** Lets a client poll while the worker is still compressing. */
    @GetMapping("/api/media/{shortCode}/status")
    public MediaStatusResponse status(@PathVariable String shortCode) {
        return mediaService.status(shortCode);
    }
}
