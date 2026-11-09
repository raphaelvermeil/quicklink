package com.quicklink.app.media;

import com.quicklink.app.link.MediaFailedException;
import com.quicklink.app.link.MediaTargetResolver;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
class PresignedMediaResolver implements MediaTargetResolver {

    private final MediaAssetRepository assets;
    private final S3Presigner presigner;
    private final String processedBucket;
    private final Duration signatureTtl;

    PresignedMediaResolver(MediaAssetRepository assets,
                           S3Presigner presigner,
                           @Value("${app.s3.buckets.processed}") String processedBucket,
                           @Value("${app.s3.presign-ttl:PT15M}") Duration signatureTtl) {
        this.assets = assets;
        this.presigner = presigner;
        this.processedBucket = processedBucket;
        this.signatureTtl = signatureTtl;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveMediaUrl(Long mediaAssetId) {
        if (mediaAssetId == null) {
            return Optional.empty();
        }
        Optional<MediaAsset> found = assets.findById(mediaAssetId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        MediaAsset asset = found.get();
        if (asset.getStatus() == MediaStatus.FAILED) {
            throw new MediaFailedException(String.valueOf(mediaAssetId));
        }
        if (asset.getStatus() != MediaStatus.READY) {
            return Optional.empty();       // PENDING or PROCESSING: come back later
        }
        return Optional.of(presign(asset.getProcessedKey()));
    }

    /**
     * Presigning is local HMAC work — no call to the object store — so generating a
     * fresh, short-lived URL per request is cheap. That is why media links are not
     * cached: a cached URL would eventually outlive its own signature.
     */
    private String presign(String processedKey) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(signatureTtl)
                .getObjectRequest(get -> get.bucket(processedBucket).key(processedKey))
                .build();
        return presigner.presignGetObject(request).url().toString();
    }
}
