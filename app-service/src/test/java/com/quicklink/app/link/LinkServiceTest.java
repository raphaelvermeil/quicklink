package com.quicklink.app.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LinkServiceTest {

    @Autowired
    LinkService linkService;

    @Test
    void createsLinkWithGeneratedShortCode() {
        Link link = linkService.createLink("https://example.com/some/very/long/path", null);

        assertNotNull(link.getId());
        assertNotNull(link.getShortCode());
        assertTrue(link.getShortCode().length() >= 5);
        assertEquals(TargetType.URL, link.getTargetType());
        assertEquals("https://example.com/some/very/long/path", link.getTargetUrl());
    }

    @Test
    void assignsDistinctCodesToDifferentLinks() {
        Link a = linkService.createLink("https://example.com/a", null);
        Link b = linkService.createLink("https://example.com/b", null);

        assertNotEquals(a.getShortCode(), b.getShortCode());
    }

    /**
     * The only test that forces the INSERT to reach Postgres: resolve() runs a query,
     * and a query flushes pending writes first. This is what caught the
     * "short_code violates not-null constraint" bug.
     */
    @Test
    void resolvesACreatedLink() {
        Link created = linkService.createLink("https://example.com/x", null);

        assertEquals("https://example.com/x",
                     linkService.resolve(created.getShortCode()).getTargetUrl());
    }

    @Test
    void throwsWhenCodeDoesNotExist() {
        assertThrows(LinkNotFoundException.class, () -> linkService.resolve("nope99"));
    }

    /** Guards the `expiresAt != null &&` check in resolve(). */
    @Test
    void throwsWhenLinkHasExpired() {
        Link expired = linkService.createLink("https://example.com/old",
                                              Instant.now().minusSeconds(60));

        assertThrows(LinkNotFoundException.class,
                     () -> linkService.resolve(expired.getShortCode()));
    }

    @Test
    void resolvesLinkWithNoExpiry() {
        Link permanent = linkService.createLink("https://example.com/forever", null);

        assertEquals("https://example.com/forever",
                     linkService.resolve(permanent.getShortCode()).getTargetUrl());
    }
}
