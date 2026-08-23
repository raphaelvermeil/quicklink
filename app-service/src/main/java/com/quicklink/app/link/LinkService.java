package com.quicklink.app.link;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    private final LinkRepository linkRepository;

    public LinkService(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    @Transactional
    public Link createLink(String longUrl, Instant expiresAt) {
        long codeValue = linkRepository.nextCodeValue();

        Link link = new Link();
        link.setShortCode(Base62.encode(codeValue));   // set BEFORE save
        link.setTargetType(TargetType.URL);
        link.setTargetUrl(longUrl);
        link.setExpiresAt(expiresAt);

        return linkRepository.save(link);              // one clean INSERT
    }

    @Transactional(readOnly = true)
    public Link resolve(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(Instant.now())) {
            throw new LinkNotFoundException(shortCode);
        }
        return link;
    }
}