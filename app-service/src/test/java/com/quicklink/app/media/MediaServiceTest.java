package com.quicklink.app.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quicklink.app.link.Link;
import com.quicklink.app.link.LinkRepository;
import com.quicklink.app.link.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Note this class is @Transactional, so nothing commits — which means the
 * AFTER_COMMIT publisher never fires and these tests leave the queue untouched.
 * S3 has no transaction, so each test deletes the object it uploaded.
 */
@SpringBootTest
@Transactional
class MediaServiceTest {

    private static final byte[] CONTENT = "not-really-a-png-but-bytes-are-bytes".getBytes();

    @Autowired MediaService mediaService;
    @Autowired MediaAssetRepository assetRepository;
    @Autowired LinkRepository linkRepository;
    @Autowired S3Client s3;

    @Value("${app.s3.buckets.originals}")
    String originalsBucket;

    @Test
    void uploadStoresTheObjectAndCreatesAPendingAssetWithAMediaLink() throws Exception {
        MediaUploadResponse response = mediaService.upload(
                new MockMultipartFile("file", "pic.png", "image/png", CONTENT));

        assertEquals(MediaStatus.PENDING, response.status(),
                "the caller is told the work has not happened yet");
        assertNotNull(response.shortCode());

        Link link = linkRepository.findByShortCode(response.shortCode()).orElseThrow();
        assertEquals(TargetType.MEDIA, link.getTargetType());
        assertNotNull(link.getMediaAssetId());

        MediaAsset asset = assetRepository.findById(link.getMediaAssetId()).orElseThrow();
        assertEquals(MediaStatus.PENDING, asset.getStatus());
        assertEquals("image/png", asset.getContentType());
        assertEquals(CONTENT.length, asset.getOriginalBytes());

        // the bytes really reached object storage, not just the database
        long storedBytes = s3.headObject(b -> b.bucket(originalsBucket)
                .key(asset.getOriginalKey())).contentLength();
        assertEquals(CONTENT.length, storedBytes);

        s3.deleteObject(b -> b.bucket(originalsBucket).key(asset.getOriginalKey()));
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThrows(EmptyUploadException.class, () -> mediaService.upload(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])));
    }
}
