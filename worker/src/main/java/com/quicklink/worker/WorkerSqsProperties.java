package com.quicklink.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param queueUrl    the job queue, which Terraform outputs
 * @param dlqUrl      the redrive target; polled only to record FAILED, mirroring
 *                    MediaDlqListener on the Rabbit side
 * @param endpoint    set for a local SQS-compatible server (ElasticMQ); blank for real SQS
 * @param waitSeconds long-poll duration: one held-open request instead of a busy loop
 */
@ConfigurationProperties(prefix = "app.sqs")
record WorkerSqsProperties(String queueUrl, String dlqUrl, String endpoint, String region,
                           String accessKey, String secretKey, Integer waitSeconds) {

    WorkerSqsProperties {
        region = (region == null || region.isBlank()) ? "us-east-1" : region;
        endpoint = endpoint == null ? "" : endpoint;
        accessKey = accessKey == null ? "" : accessKey;
        secretKey = secretKey == null ? "" : secretKey;
        dlqUrl = dlqUrl == null ? "" : dlqUrl;
        waitSeconds = waitSeconds == null ? 20 : waitSeconds;
    }
}
