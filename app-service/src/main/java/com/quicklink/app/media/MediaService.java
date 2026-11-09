package com.quicklink.app.media;

import com.quicklink.app.link.Base62;
import com.quicklink.app.link.Link;
import com.quicklink.app.link.LinkNotFoundException;
import com.quicklink.app.link.LinkRepository;
import com.quicklink.app.link.TargetType;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class MediaService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final S3Client s3;
    private final MediaAssetRepository assetRepository;
    private final LinkRepository linkRepository;
    private final ApplicationEventPublisher events;
    private final String originalsBucket;
    private final String baseUrl;

    public MediaService(S3Client s3,
                        MediaAssetRepository assetRepository,
                        LinkRepository linkRepository,
                        ApplicationEventPublisher events,
                        @Value("${app.s3.buckets.originals}") String originalsBucket,
                        @Value("${app.base-url}") String baseUrl) {
        this.s3 = s3;
        this.assetRepository = assetRepository;
        this.linkRepository = linkRepository;
        this.events = events;
        this.originalsBucket = originalsBucket;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public MediaUploadResponse upload(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new EmptyUploadException();
        }

        String objectKey = UUID.randomUUID().toString();
        String contentType = file.getContentType() == null ? DEFAULT_CONTENT_TYPE : file.getContentType();

        // Upload before touching the database: if S3 fails we want no rows at all,
        // rather than an asset row pointing at an object that was never stored.
        // Streaming from the multipart's input stream keeps the file off the heap.
        s3.putObject(PutObjectRequest.builder()
                        .bucket(originalsBucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength(file.getSize())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        MediaAsset asset = new MediaAsset();
        asset.setStatus(MediaStatus.PENDING);
        asset.setContentType(contentType);
        asset.setOriginalKey(objectKey);
        asset.setOriginalBytes(file.getSize());
        assetRepository.save(asset);          // IDENTITY: the id is available immediately

        long codeValue = linkRepository.nextCodeValue();
        Link link = new Link();
        link.setShortCode(Base62.encode(codeValue));
        link.setTargetType(TargetType.MEDIA);
        link.setMediaAssetId(asset.getId());  // the V4 foreign key requires the asset first
        linkRepository.save(link);

        // Not published here — see MediaJobPublisher.
        events.publishEvent(new MediaUploadedEvent(asset.getId(), objectKey));

        return new MediaUploadResponse(link.getShortCode(),
                baseUrl + "/" + link.getShortCode(), MediaStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public MediaStatusResponse status(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        if (link.getTargetType() != TargetType.MEDIA || link.getMediaAssetId() == null) {
            throw new LinkNotFoundException(shortCode);   // a URL link has no media status
        }

        MediaAsset asset = assetRepository.findById(link.getMediaAssetId())
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        return new MediaStatusResponse(shortCode, asset.getStatus(),
                asset.getOriginalBytes(), asset.getProcessedBytes(), asset.getErrorMessage());
    }
}
