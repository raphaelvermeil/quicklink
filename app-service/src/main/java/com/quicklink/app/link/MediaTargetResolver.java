package com.quicklink.app.link;

import java.util.Optional;

/**
 * Resolves where a MEDIA link should redirect.
 *
 * Declared in this package but implemented in the media package, so the dependency
 * points inward: LinkService stays ignorant of S3, presigning and MediaStatus.
 */
public interface MediaTargetResolver {

    /**
     * @return the URL to redirect to, or empty if the asset is still being processed.
     * @throws MediaFailedException if processing failed permanently, so the caller can
     *         answer 410 rather than inviting the client to keep polling.
     */
    Optional<String> resolveMediaUrl(Long mediaAssetId);
}
