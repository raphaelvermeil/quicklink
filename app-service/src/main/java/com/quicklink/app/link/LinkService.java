package com.quicklink.app.link;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class LinkService {


    private static final String KEY_PREFIX  = "link:code:";
    private static final String MISSING     = "__missing__";
    private static final Duration MAX_TTL     = Duration.ofHours(1);
    private static final Duration MISSING_TTL = Duration.ofSeconds(30);

    private final LinkRepository linkRepository;
    private final LinkStatsRepository statsRepository;
    private final StringRedisTemplate redis;
    private final MediaTargetResolver mediaTargets;

    public LinkService(LinkRepository linkRepository,
                       LinkStatsRepository statsRepository,
                       StringRedisTemplate redis,
                       MediaTargetResolver mediaTargets) {
        this.linkRepository = linkRepository;
        this.statsRepository = statsRepository;
        this.redis = redis;
        this.mediaTargets = mediaTargets;
    }


    public String resolveTargetUrl(String shortCode) {
        String key = KEY_PREFIX + shortCode;

        String cached = redis.opsForValue().get(key);
        if (MISSING.equals(cached)) {
            throw new LinkNotFoundException(shortCode);
        }
        // A blank value is treated as a miss, not a hit. An earlier bug cached an
        // empty string for media links (whose target_url is null), and the fix alone
        // would not have healed the already-poisoned entries.
        if (cached != null && !cached.isBlank()) {
            recordClick(shortCode);
            return cached;
        }

        Optional<Link> found = linkRepository.findByShortCode(shortCode);
        if (found.isEmpty() || isExpired(found.get())) {
            redis.opsForValue().set(key, MISSING, MISSING_TTL);   // negative cache
            throw new LinkNotFoundException(shortCode);
        }


        Link link = found.get();

        // MEDIA links are deliberately NOT cached. Their status changes as the worker
        // progresses, and the presigned URL they resolve to expires. Caching either
        // would eventually serve a stale status or a dead signature.
        if (link.getTargetType() == TargetType.MEDIA) {
            String mediaUrl = mediaTargets.resolveMediaUrl(link.getMediaAssetId())
                    .orElseThrow(() -> new MediaNotReadyException(shortCode));
            recordClick(shortCode);
            return mediaUrl;
        }

        Duration ttl = cacheTtlFor(link);
        if (ttl.isPositive() && link.getTargetUrl() != null && !link.getTargetUrl().isBlank()) {
            redis.opsForValue().set(key, link.getTargetUrl(), ttl);
        }


        recordClick(shortCode);
        return link.getTargetUrl();
    }

    private void recordClick(String shortCode) {
        redis.opsForValue().increment(ClickCounters.counterKey(shortCode));
        redis.opsForSet().add(ClickCounters.DIRTY_SET, shortCode);
    }


    private boolean isExpired(Link link) {
        return link.getExpiresAt() != null && link.getExpiresAt().isBefore(Instant.now());
    }

    private Duration cacheTtlFor(Link link) {
        if (link.getExpiresAt() == null) {
            return MAX_TTL;
        }
        Duration untilExpiry = Duration.between(Instant.now(), link.getExpiresAt());
        return untilExpiry.compareTo(MAX_TTL) < 0 ? untilExpiry : MAX_TTL;
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

    @Transactional(readOnly = true)
    public long clickCount(String shortCode) {
        if (linkRepository.findByShortCode(shortCode).isEmpty()) {
            throw new LinkNotFoundException(shortCode);
        }

        long persisted = statsRepository.findById(shortCode)
                .map(LinkStats::getClicks)
                .orElse(0L);

        String pending = redis.opsForValue().get(ClickCounters.counterKey(shortCode));

        return persisted + (pending == null ? 0L : Long.parseLong(pending));
    }   

}