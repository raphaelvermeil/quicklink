package com.quicklink.app.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param queueUrl the full SQS queue URL, which Terraform outputs
 * @param dlqUrl   the dead-letter queue. The producer never sends to it; it is here so
 *                 the demo inspect endpoint can report its depth, as it does for Rabbit
 * @param endpoint set for a local SQS-compatible server (ElasticMQ); leave blank for real SQS
 * @param region   required by request signing even when a local server ignores it
 */
@ConfigurationProperties(prefix = "app.sqs")
public record SqsProperties(String queueUrl, String dlqUrl, String endpoint, String region,
                            String accessKey, String secretKey) {

    public SqsProperties {
        region = (region == null || region.isBlank()) ? "us-east-1" : region;
        endpoint = endpoint == null ? "" : endpoint;
        accessKey = accessKey == null ? "" : accessKey;
        secretKey = secretKey == null ? "" : secretKey;
        dlqUrl = dlqUrl == null ? "" : dlqUrl;
    }
}
