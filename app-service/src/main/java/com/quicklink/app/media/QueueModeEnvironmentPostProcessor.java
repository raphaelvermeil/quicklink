package com.quicklink.app.media;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Takes RabbitMQ out of the picture when app.queue=sqs.
 *
 * Every Rabbit bean of ours is already @ConditionalOnProperty, but Boot's own
 * RabbitAutoConfiguration is not — and in a deployment with no broker it contributes a
 * health indicator that reports DOWN with "Connection refused". Because the health
 * endpoint aggregates, that one component turns /actuator/health into a 503 while
 * nothing is actually wrong: the SQS deployment has no broker on purpose. (Routing
 * survived it only because edge-lb probes /actuator/health/readiness, a group that does
 * not include rabbit.)
 *
 * This has to be an EnvironmentPostProcessor rather than a @Conditional. An
 * auto-configuration can only be switched off through a property that is already set by
 * the time the context is built, and Boot has no way to make one property's value depend
 * on another's — there is spring.config.activate.on-profile, but no on-property. So
 * app.queue stays the single switch that selects the transport, and this derives the rest
 * of the configuration from it instead of asking the deployment to repeat itself.
 */
public class QueueModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!"sqs".equals(environment.getProperty("app.queue"))) {
            return;
        }
        // addLast, not addFirst: these are defaults derived from app.queue, and an
        // operator who sets either one explicitly should still win.
        environment.getPropertySources().addLast(new MapPropertySource("quicklink-sqs-mode", Map.of(
                // Keeps rabbit out of the health aggregate. Redundant while the
                // exclusion below holds — the indicator is @ConditionalOnBean on a
                // RabbitTemplate that no longer exists — but says so directly rather
                // than leaving it as a side effect of bean absence.
                "management.health.rabbit.enabled", "false",
                // And nothing builds a connection factory to begin with.
                "spring.autoconfigure.exclude", RabbitAutoConfiguration.class.getName())));
    }

    /**
     * After ConfigDataEnvironmentPostProcessor, which is what loads application.yaml:
     * before it, app.queue is not readable yet and this would see sqs mode as rabbit.
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
