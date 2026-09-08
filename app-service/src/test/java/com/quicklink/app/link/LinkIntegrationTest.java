package com.quicklink.app.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LinkIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    /**
     * Redis must be isolated too. Without this the test wrote click counters into the
     * shared dev Redis for links that only ever existed in the throwaway Postgres above,
     * orphaning counters whose foreign key could never be satisfied.
     */
    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:7");

    @Autowired
    MockMvc mockMvc;

    @Test
    void createsALinkAndRedirectsToTheTarget() throws Exception {
        String body = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/some/very/long/path\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.shortUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortCode = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                                           "https://example.com/some/very/long/path"));
    }

    @Test
    void rejectsAMalformedUrl() throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"not a url\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/zzzzz"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // Rate limiting (piece 4).
    //
    // These rely on this class having its OWN Redis container: buckets start
    // empty and cannot be polluted by (or pollute) the dev Redis. Each test
    // uses a distinct client IP so it owns its bucket outright.
    // ---------------------------------------------------------------------

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions createLinkFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/burst\"}")
                .with(fromIp(ip)));
    }

    @Test
    void allowsABurstUpToCapacityThenReturns429() throws Exception {
        for (int i = 1; i <= 10; i++) {
            createLinkFrom("10.0.0.10").andExpect(status().isCreated());
        }

        createLinkFrom("10.0.0.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /** The limit is per client, not global — otherwise one user could lock out everyone. */
    @Test
    void oneClientExhaustingItsBucketDoesNotAffectAnother() throws Exception {
        for (int i = 1; i <= 10; i++) {
            createLinkFrom("10.0.0.20").andExpect(status().isCreated());
        }
        createLinkFrom("10.0.0.20").andExpect(status().isTooManyRequests());

        createLinkFrom("10.0.0.21").andExpect(status().isCreated());
    }

    /** shouldNotFilter() must keep the redirect hot path unmetered. */
    @Test
    void redirectsAreNeverRateLimited() throws Exception {
        String body = createLinkFrom("10.0.0.30")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(get("/" + shortCode).with(fromIp("10.0.0.30")))
                   .andExpect(status().isFound());
        }
    }
}
