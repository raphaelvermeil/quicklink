package com.quicklink.app.media;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Only created when app.queue=sqs, so the default Rabbit deployment pulls in no SQS
 * client at all. Dual-mode in the same way as S3: an endpoint means a local
 * SQS-compatible server, no endpoint means real AWS.
 */
@Configuration
@ConditionalOnProperty(name = "app.queue", havingValue = "sqs")
@EnableConfigurationProperties(SqsProperties.class)
public class SqsConfig {

    @Bean
    SqsClient sqsClient(SqsProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties));
        if (!properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    static AwsCredentialsProvider credentials(SqsProperties properties) {
        if (properties.accessKey().isBlank() || properties.secretKey().isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
