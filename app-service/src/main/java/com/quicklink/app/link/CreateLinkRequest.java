package com.quicklink.app.link;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.hibernate.validator.constraints.URL;

public record CreateLinkRequest(
        @NotBlank(message = "longUrl is required")
        @URL(message = "longUrl must be a valid URL")
        String longUrl,

        Instant expiresAt
) {}
