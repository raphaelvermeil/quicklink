package com.quicklink.app.link;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
public class LinkController {

    private final LinkService linkService;
    private final String baseUrl;

    public LinkController(LinkService linkService,
                          @Value("${app.base-url}") String baseUrl) {
        this.linkService = linkService;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/api/links")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateLinkResponse create(@Valid @RequestBody CreateLinkRequest request) {
        Link link = linkService.createLink(request.longUrl(), request.expiresAt());

        return new CreateLinkResponse(link.getShortCode(),
                                      baseUrl + "/" + link.getShortCode());
    }

    /**
     * Preview: resolves a code and reports its target without redirecting.
     *
     * Deliberately uses the uncached resolve() rather than the redirect path, so
     * inspecting a link neither records a click nor warms the cache — otherwise
     * looking at a link would change the numbers you were looking at.
     */
    @GetMapping("/api/links/{shortCode}")
    public LinkPreviewResponse preview(@PathVariable String shortCode) {
        Link link = linkService.resolve(shortCode);
        return new LinkPreviewResponse(link.getShortCode(), link.getTargetType(),
                link.getTargetUrl(), link.getMediaAssetId(), link.getExpiresAt());
    }

    @GetMapping("/api/links/{shortCode}/stats")
    public LinkStatsResponse stats(@PathVariable String shortCode) {
        return new LinkStatsResponse(shortCode, linkService.clickCount(shortCode));
    }

    @GetMapping("/{shortCode:[0-9a-zA-Z]{5,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String targetUrl = linkService.resolveTargetUrl(shortCode);

        return ResponseEntity.status(HttpStatus.FOUND)
                             .location(URI.create(targetUrl))
                             .build();
    }
}
