package com.quicklink.app.media;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    /**
     * Points the standard AWS S3 client at a local S3-compatible store. The application
     * code is ordinary AWS SDK v2 — only the endpoint differs from real S3, which is why
     * swapping MinIO for SeaweedFS changed nothing but configuration.
     */
    @Bean
    S3Client s3Client(@Value("${app.s3.endpoint}") String endpoint,
                      @Value("${app.s3.access-key}") String accessKey,
                      @Value("${app.s3.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // Region is required by the SDK's signing process even though a local
                // store ignores it.
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // Essential: without this the SDK builds virtual-host-style URLs like
                // http://originals.seaweedfs:8333/key, which does not resolve.
                .forcePathStyle(true)
                .build();
    }

    /**
     * Signed against a PUBLICLY reachable endpoint, not the internal one.
     *
     * The app talks to the store over the compose network as http://seaweedfs:8333,
     * but a presigned URL is handed to a browser that cannot resolve that hostname.
     * The signature covers the host, so the public address has to be used at signing
     * time rather than rewritten afterwards.
     */
    @Bean
    S3Presigner s3Presigner(@Value("${app.s3.public-endpoint:${app.s3.endpoint}}") String publicEndpoint,
                            @Value("${app.s3.access-key}") String accessKey,
                            @Value("${app.s3.secret-key}") String secretKey) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // The presigner takes path-style via serviceConfiguration, unlike
                // S3Client's forcePathStyle().
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
