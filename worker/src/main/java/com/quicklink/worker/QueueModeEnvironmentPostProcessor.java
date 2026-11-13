package com.quicklink.worker;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Takes RabbitMQ out of the picture when app.queue=sqs, as app-service does.
 *
 * The worker has no actuator and so no health endpoint to break; what this removes here
 * is the connection factory itself. Boot builds that lazily, so in SQS mode it never
 * actually dialled a broker — the listeners that would have used it are all
 * @ConditionalOnProperty. It is removed anyway, because "the worker holds AMQP
 * infrastructure it can never use" is the kind of thing that is true until someone adds
 * a bean that touches it.
 *
 * Why an EnvironmentPostProcessor and not a @Conditional: see the app-service copy. The
 * two are deliberately separate — these are independently deployable modules with no
 * shared code, and one class each is cheaper than a module to hold it.
 */
public class QueueModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!"sqs".equals(environment.getProperty("app.queue"))) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("quicklink-sqs-mode",
                Map.of("spring.autoconfigure.exclude", RabbitAutoConfiguration.class.getName())));
    }

    /** After the processor that loads application.yaml, or app.queue is not readable yet. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
