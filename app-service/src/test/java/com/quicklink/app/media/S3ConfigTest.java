package com.quicklink.app.media;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Pins the dual-mode S3 wiring without a Spring context, network, or AWS account.
 *
 * Presigning is pure local signing — no call is made to the store — so the URL the
 * presigner produces is enough to prove which world the client is addressing and
 * whether it is using path-style or virtual-host addressing. That makes the real-AWS
 * branch testable offline, which otherwise could only be checked by deploying.
 */
class S3ConfigTest {

    private static final String KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private static String presignedUrl(S3Presigner presigner) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(get -> get.bucket("my-bucket").key("some/object.png"))
                .build()).url().toString();
    }

    @Test
    void addressesRealAwsWhenNoEndpointIsConfigured() {
        String url = presignedUrl(new S3Config().s3Presigner("", "eu-west-1", KEY, SECRET));

        assertTrue(url.startsWith("https://my-bucket.s3.eu-west-1.amazonaws.com/some/object.png"),
                "with no endpoint it must target the regional AWS endpoint in virtual-host "
                        + "style, got: " + url);
        assertTrue(url.contains("X-Amz-Signature="), "must still be signed: " + url);
    }

    @Test
    void addressesTheLocalStoreInPathStyleWhenAnEndpointIsConfigured() {
        String url = presignedUrl(
                new S3Config().s3Presigner("http://localhost:8333", "us-east-1", KEY, SECRET));

        assertTrue(url.startsWith("http://localhost:8333/my-bucket/some/object.png"),
                "a local store needs path-style addressing — virtual-host URLs like "
                        + "http://my-bucket.localhost:8333 do not resolve. Got: " + url);
    }

    @Test
    void theRegionIsHonoured() {
        String url = presignedUrl(new S3Config().s3Presigner("", "ap-southeast-2", KEY, SECRET));

        assertTrue(url.contains("s3.ap-southeast-2.amazonaws.com"), url);
        assertTrue(url.contains("%2Fap-southeast-2%2Fs3%2Faws4_request"),
                "the region must appear in the signing scope too: " + url);
    }
}
