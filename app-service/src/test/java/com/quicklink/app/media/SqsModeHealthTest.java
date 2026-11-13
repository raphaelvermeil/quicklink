package com.quicklink.app.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quicklink.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * In SQS mode the health endpoint must not report on a broker the deployment does not
 * have.
 *
 * The bug this pins: on Azure, where the stack runs app.queue=sqs and no RabbitMQ
 * container exists, /actuator/health returned 503. Nothing was broken — Boot's
 * RabbitAutoConfiguration had contributed a health indicator, it could not reach a
 * broker that was never deployed, and one DOWN component takes the whole aggregate down
 * with it. DEPLOY.md step 3.4 asks the reader to expect 200 from that URL.
 *
 * Note what carries the weight here. This test inherits a live RabbitMQ container from
 * IntegrationTest, so the status assertion alone would pass either way — a reachable
 * broker reports UP. The assertion that bites is that there is no rabbit component at
 * all: a component that does not exist cannot report DOWN wherever the stack runs.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.queue=sqs",
        // Never published to; the SQS client only has to build, which it does offline.
        "app.sqs.queue-url=http://localhost/000000000000/unused-by-this-test",
        "app.sqs.access-key=test",
        "app.sqs.secret-key=test-secret",
})
class SqsModeHealthTest extends IntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Test
    void healthIsUpAndSaysNothingAboutRabbit() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // The whole point: no broker in this deployment, so no broker in the
                // health report either.
                .andExpect(jsonPath("$.components.rabbit").doesNotExist())
                // The components that are real still have to be there, or this test
                // would also pass against a health endpoint that had stopped working.
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void rabbitAutoConfigurationIsExcludedEntirely() {
        assertEquals(0, context.getBeanNamesForType(RabbitTemplate.class).length,
                "app.queue=sqs must exclude RabbitAutoConfiguration — the health "
                        + "indicator is @ConditionalOnBean(RabbitTemplate), so this bean "
                        + "being absent is what keeps it from being registered");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "and no connection factory should exist to be dialled by accident");
    }
}
