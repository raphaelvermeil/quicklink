package com.quicklink.app.link;

import java.time.Instant;

/** What a short code points at, without following it. */
public record LinkPreviewResponse(String shortCode,
                                  TargetType targetType,
                                  String targetUrl,
                                  Long mediaAssetId,
                                  Instant expiresAt) {
}
