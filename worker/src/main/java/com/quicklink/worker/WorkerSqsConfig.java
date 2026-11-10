package com.quicklink.worker;

import java.net.URI;
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The SQS half of the worker, created only when app.queue=sqs. In the default Rabbit
 * deployment none of these beans exist and no SQS client is ever built.
 */
@Configuration
@ConditionalOnProperty(name = "app.queue", havingValue = "sqs")
@EnableConfigurationProperties(WorkerSqsProperties.class)
class WorkerSqsConfig {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(WorkerSqsProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties));
        if (!properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    SqsQueuePoller jobPoller(SqsClient sqs, ObjectMapper json, WorkerSqsProperties properties,
                             MediaCompressHandler handler) {
        return new SqsQueuePoller("jobs", properties.queueUrl(), sqs, json,
                properties.waitSeconds(), handler::handle);
    }

    /**
     * The counterpart to MediaDlqListener: without it, a job the redrive policy gives
     * up on would leave its asset stuck in PROCESSING forever, invisible in the API.
     */
    @Bean
    @ConditionalOnProperty(name = "app.sqs.dlq-url")
    SqsQueuePoller dlqPoller(SqsClient sqs, ObjectMapper json, WorkerSqsProperties properties,
                             MediaAssetStore assets) {
        return new SqsQueuePoller("dlq", properties.dlqUrl(), sqs, json,
                properties.waitSeconds(), job -> {
            LoggerFactory.getLogger(WorkerSqsConfig.class)
                    .error("[dlq] asset {} gave up after retries (key {})",
                            job.assetId(), job.originalKey());
            assets.markFailedIfNotReady(job.assetId(), "processing failed after retries");
        });
    }

    private static AwsCredentialsProvider credentials(WorkerSqsProperties properties) {
        if (properties.accessKey().isBlank() || properties.secretKey().isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
