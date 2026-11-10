package com.quicklink.worker;

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

/**
 * Same dual-mode arrangement as app-service's S3Config:
 *   app.s3.endpoint set    -> a local S3-compatible store
 *   app.s3.endpoint absent -> real AWS S3
 *
 * The worker needs no presigner; it only reads originals and writes processed objects.
 */
@Configuration
public class WorkerS3Config {

    @Bean
    S3Client s3Client(@Value("${app.s3.endpoint:}") String endpoint,
                      @Value("${app.s3.region:us-east-1}") String region,
                      @Value("${app.s3.access-key:}") String accessKey,
                      @Value("${app.s3.secret-key:}") String secretKey) {

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey));

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
