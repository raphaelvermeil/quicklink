package com.quicklink.app.media;

public record MediaStatusResponse(String shortCode,
                                  MediaStatus status,
                                  Long originalBytes,
                                  Long processedBytes,
                                  String errorMessage) {
}
