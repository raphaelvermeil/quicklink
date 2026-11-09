package com.quicklink.app.media;

public record MediaUploadResponse(String shortCode, String shortUrl, MediaStatus status) {
}
