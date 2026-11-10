package com.quicklink.app.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which broker the application publishes to is a wiring decision, so it is tested as
 * one — with an ApplicationContextRunner rather than a @SpringBootTest. No containers,
 * no database, no broker: just the conditions being evaluated.
 *
 * Worth pinning, because the failure mode is quiet. If RabbitConfig stopped being
 * conditional, an SQS deployment would still start and still work — right up until the
 * Rabbit beans tried to declare a topology against a broker nobody deployed.
 */
class MediaJobQueueSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Collaborators both implementations need, stubbed: this test is about
            // which bean is chosen, not about what it then does.
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean(SqsClient.class, () -> mock(SqsClient.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(SqsProperties.class,
                    () -> new SqsProperties("http://queue", "", "", "", "", ""))
            .withUserConfiguration(RabbitMediaJobQueue.class, SqsMediaJobQueue.class);

    @Test
    void rabbitIsTheDefaultWhenNothingIsConfigured() {
        runner.run(context -> assertThat(context).getBean(MediaJobQueue.class)
                .isInstanceOf(RabbitMediaJobQueue.class));
    }

    @Test
    void sqsIsSelectedByConfigurationAlone() {
        runner.withPropertyValues("app.queue=sqs")
                .run(context -> assertThat(context).getBean(MediaJobQueue.class)
                        .isInstanceOf(SqsMediaJobQueue.class));
    }

    /** Exactly one, either way: two publishers would be an ambiguous injection point. */
    @Test
    void onlyEverOneImplementationExists() {
        runner.run(context -> assertThat(context.getBeansOfType(MediaJobQueue.class)).hasSize(1));
        runner.withPropertyValues("app.queue=rabbit")
                .run(context -> assertThat(context.getBeansOfType(MediaJobQueue.class)).hasSize(1));
        runner.withPropertyValues("app.queue=sqs")
                .run(context -> assertThat(context.getBeansOfType(MediaJobQueue.class)).hasSize(1));
    }
}
