package com.quicklink.app.media;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * One S3 client for two worlds, chosen by configuration rather than by code.
 *
 *   app.s3.endpoint set    → a local S3-compatible store (SeaweedFS in compose)
 *   app.s3.endpoint absent → real AWS S3 at its regional endpoint
 *
 * Nothing in the application changes between the two. That is the whole point of
 * programming against the S3 API rather than a vendor's own SDK.
 */
@Configuration
public class S3Config {

    @Bean
    S3Client s3Client(@Value("${app.s3.endpoint:}") String endpoint,
                      @Value("${app.s3.region:us-east-1}") String region,
                      @Value("${app.s3.access-key:}") String accessKey,
                      @Value("${app.s3.secret-key:}") String secretKey) {

        S3ClientBuilder builder = S3Client.builder()
                // Required by the SDK's request signing even when a local store ignores it.
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey));

        if (!endpoint.isBlank()) {
            // Local store: an explicit endpoint, and path-style addressing because
            // virtual-host URLs like http://bucket.seaweedfs:8333/key do not resolve.
            // Real S3 wants neither — it has regional endpoints and prefers
            // virtual-host style, which is the SDK default.
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    /**
     * Signed against a publicly reachable address.
     *
     * The app reaches a local store over the compose network as http://seaweedfs:8333,
     * but a presigned URL is handed to a browser that cannot resolve that hostname —
     * and the signature covers the host, so it cannot be rewritten afterwards. Against
     * real S3 this is moot: the regional endpoint is already public, so no override is
     * needed at all.
     */
    @Bean
    S3Presigner s3Presigner(@Value("${app.s3.public-endpoint:${app.s3.endpoint:}}") String publicEndpoint,
                            @Value("${app.s3.region:us-east-1}") String region,
                            @Value("${app.s3.access-key:}") String accessKey,
                            @Value("${app.s3.secret-key:}") String secretKey) {

        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey));

        if (!publicEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(publicEndpoint))
                    // The presigner takes path-style here, not via forcePathStyle().
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }

    /**
     * Static keys when they are configured, otherwise the SDK's default chain
     * (environment, shared profile, container or instance role). On AWS the default
     * chain is what you want: an instance role means no long-lived secret has to exist
     * in configuration at all.
     */
    private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            // builder(), not the deprecated create()
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
