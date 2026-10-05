# Quicklink

A URL shortener built on **Spring Boot 4 / Java 21**, backed by **Postgres** for
durability and **Redis** for the hot path. It shortens a long URL to a Base62
code, serves the redirect out of cache, counts clicks without touching the
database on every hit, and rate-limits creation across instances.

The interesting part isn't the shortening — it's everything around it: the
read-through cache with negative caching, the write-behind click counter, and a
distributed token bucket that still holds when you run more than one copy of the
service.

---

## Quick start

Requires **Java 21** and **Docker**.

```bash
docker compose up -d          # Postgres on 5434, Redis on 6380
./mvnw -pl app-service spring-boot:run
```

The service listens on **8081**. Flyway runs the migrations on boot.

```bash
# shorten
curl -X POST localhost:8081/api/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/some/very/long/path"}'
# → 201 {"shortCode":"10000","shortUrl":"http://localhost:8081/10000"}

# follow it
curl -i localhost:8081/10000
# → 302, Location: https://example.com/some/very/long/path

# click count
curl localhost:8081/api/links/10000/stats
# → {"shortCode":"10000","clicks":1}
```

---

## API

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/api/links` | `{longUrl, expiresAt?}` → `201 {shortCode, shortUrl}`. Validates the URL; rate-limited per IP. |
| `GET` | `/{shortCode}` | `302` to the target. `404` if unknown or expired. Codes are 5–16 alphanumerics, so this never shadows `/api/...` or `/actuator/...`. |
| `GET` | `/api/links/{shortCode}/stats` | `{shortCode, clicks}` — persisted count plus whatever is still pending in Redis. |
| `GET` | `/actuator/health`, `/actuator/info` | Actuator. |

---

## How it works

### Short codes

Codes come from a Postgres sequence (`link_code_seq`) Base62-encoded — no random
generation, so **no collision check and no retry loop**: one `nextval`, one
`INSERT`. The sequence starts at `14776336` (62⁴), which guarantees every code is
at least five characters.

The tradeoff is that codes are **enumerable** — `10000` implies `10001` probably
exists. That's accepted deliberately; the mitigation is rate limiting rather than
obfuscating the counter, since XOR-ing it with a fixed key only hides creation
order from someone who hasn't seen a few pairs.

### The redirect path

`GET /{code}` tries Redis first and only falls through to Postgres on a miss:

- **Hit** → return the cached target immediately.
- **Miss** → read Postgres, cache the target, return it. TTL is capped at **1
  hour** and clamped to the link's own expiry, so a cached entry can never
  outlive the link.
- **Unknown or expired** → cache a `__missing__` sentinel for **30 seconds** and
  return `404`. This negative caching is what stops a flood of requests for
  nonexistent codes from becoming a flood of database reads.

### Click counting

Counting on the redirect path would put a write on every single hit, so it's
**write-behind** instead:

1. Each redirect does an `INCR` on `stats:clicks:{code}` and adds the code to a
   `stats:dirty` set — two Redis ops, no database.
2. `ClickStatsFlusher` wakes every 60 s, drains the dirty set, and folds each
   delta into Postgres with an `INSERT … ON CONFLICT DO UPDATE`. It removes the
   code from the dirty set *before* reading the counter, so a click arriving
   mid-flush re-marks the code rather than being dropped.
3. The stats endpoint returns persisted + pending, so the number is never stale
   even between flushes.

### Rate limiting

`RateLimitFilter` guards **only** `POST /api/links` — redirects stay fast and
unmetered. It's a **Bucket4j** token bucket (default 10 per 60 s per IP) stored
in Redis via Lettuce with CAS, so the limit is shared by every instance of the
service rather than being per-process. Allowed requests get
`X-Rate-Limit-Remaining`; rejected ones get `429` and `Retry-After`.

One wrinkle worth knowing: `RateLimitConfig` builds its Redis client from
`DataRedisConnectionDetails`, not from the `spring.data.redis.*` properties.
Testcontainers' `@ServiceConnection` supplies a connection-details bean and
leaves the properties alone, so reading the properties directly would make tests
quietly talk to the developer's local Redis instead of the container.

### Schema

Flyway owns the schema and Hibernate is set to `ddl-auto: validate`, so the
entity and the migrations can't drift apart silently.

| Migration | Contents |
|-----------|----------|
| `V1__init.sql` | `link` — code, target type/URL, timestamps, optional expiry |
| `V2__link_code_seq.sql` | the sequence short codes are drawn from |
| `V3__link_stats.sql` | `link_stats` — the flushed click totals |

`link.target_type` is already `'URL' | 'MEDIA'` and a `media_asset_id` column is
reserved, for the upload pipeline below.

---

## Project layout

```
quicklink/
├── docker-compose.yml        Postgres 16 + Redis 7 for local dev
├── pom.xml                   parent POM (Java 21, Boot 4.1.0)
└── app-service/
    └── src/main/java/com/quicklink/app/
        ├── link/             entity, repositories, service, controller, Base62,
        │                     click counters + the scheduled flusher
        └── ratelimit/        Bucket4j filter and its Redis wiring
```

## Tests

```bash
./mvnw test
```

Unit tests cover Base62 and the service and flusher logic; the integration tests
spin up real Postgres and Redis with **Testcontainers**, so they need Docker but
no hand-started dependencies.

---

## Status

Built so far: the shortener core, caching, click analytics, and distributed rate
limiting — single service instance.

Planned, and why the POM is already multi-module: a **custom load balancer** in
front of several stateless app instances, an **async media upload and
compression pipeline** (a `worker` module, which is what `TargetType.MEDIA` is
for), and an observability/docs pass. The full reasoning lives in
[`springboot-quicklink-development-plan.md`](springboot-quicklink-development-plan.md).
