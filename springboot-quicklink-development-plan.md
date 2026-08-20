# Quicklink — Development Plan

A single portfolio project that folds three classic systems-design pieces into one coherent product:

1. **URL shortener** (a TinyURL/Bitly clone) — the front door.
2. **Media compression pipeline** — a short link can point to an uploaded file that gets compressed asynchronously.
3. **Custom load balancer** — you run several instances of the app and put your own L7 load balancer in front.

> Rename `quicklink` to whatever you like before you start. The name appears in the Maven `groupId`/`artifactId`, package names, and container names below.

---

## 1. What this project demonstrates

Building this end to end lets you speak fluently — and from experience, not from a blog — about: base62 key generation, read-heavy caching (cache-aside), rate limiting, statelessness and horizontal scaling, reverse proxying, health checks and failover, load-balancing algorithms (round-robin, least-connections), async job queues, worker pools, object storage, idempotency, dead-letter queues, and observability.

The single most important habit throughout: **every app instance must be stateless.** All state lives in Postgres, Redis, RabbitMQ, or object storage. That property is _what makes the load balancer work_, and it's the through-line that ties all three pieces together.

---

## 2. Tech stack

| Concern          | Choice                                                                                          | Why                                                                                                                                  |
| ---------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Language         | **Java 21 (LTS)**                                                                               | Records, pattern matching, and stable **virtual threads** (great for the load balancer). Java 25 LTS is fine too if you want newest. |
| Build            | **Maven, multi-module**                                                                         | One repo, several deployables (app, worker, load balancer). Ubiquitous in Java shops. Gradle is a fine alternative.                  |
| App framework    | **Spring Boot 3.3+** (take the latest 3.x from start.spring.io)                                 | Web, JPA, Redis, AMQP, Actuator all first-party. Huge resume signal.                                                                 |
| REST             | **Spring Web (MVC)**                                                                            | Standard, well understood, easy to test with MockMvc.                                                                                |
| Database         | **PostgreSQL 16**                                                                               | URL mappings + media metadata. Relational is a defensible, explainable choice.                                                       |
| Persistence      | **Spring Data JPA (Hibernate)**                                                                 | Entities, repositories, less boilerplate.                                                                                            |
| Migrations       | **Flyway**                                                                                      | Versioned, repeatable schema. Interviewers like seeing this.                                                                         |
| Cache            | **Redis 7 (Lettuce client)**                                                                    | Cache-aside on redirects, click counters, rate-limit buckets.                                                                        |
| Rate limiting    | **Bucket4j + Redis**                                                                            | Distributed token bucket that works across instances.                                                                                |
| Queue            | **RabbitMQ + Spring AMQP**                                                                      | Media compression jobs, retries, dead-letter queue.                                                                                  |
| Object storage   | **MinIO** (S3-compatible, self-hosted) + **AWS SDK v2 S3 client**                               | Learn the S3 API locally with no cloud bill; swap to real S3 later by changing config.                                               |
| Image processing | **Thumbnailator**                                                                               | Simple, dependable image resize/recompress. (ffmpeg for video is a stretch goal.)                                                    |
| Load balancer    | **Plain Java** (JDK `HttpServer` + `java.net.http.HttpClient`, virtual threads) — **no Spring** | The point is to see the mechanism. Keep it thin.                                                                                     |
| Containers       | **Docker + Docker Compose**                                                                     | Postgres, Redis, RabbitMQ, MinIO, N app instances, the LB — all wired together.                                                      |
| Testing          | **JUnit 5, Testcontainers, Mockito, MockMvc**                                                   | Testcontainers spins up real Postgres/Redis/Rabbit/MinIO in tests — a strong signal.                                                 |
| API docs         | **springdoc-openapi**                                                                           | Free Swagger UI at `/swagger-ui.html`.                                                                                               |
| Observability    | **Actuator + Micrometer + Prometheus** (+ Grafana, optional)                                    | `/actuator/health` is what your LB polls; metrics make the async pipeline visible.                                                   |
| CI               | **GitHub Actions**                                                                              | Build + test on every push.                                                                                                          |

---

## 3. Repository structure (multi-module Maven)

```
quicklink/
├── pom.xml                     # parent POM, <packaging>pom</packaging>
├── docker-compose.yml          # infra + app instances + LB
├── README.md                   # architecture diagram, decisions, run instructions
├── .github/workflows/ci.yml
│
├── app-service/                # Spring Boot: shortener + media API (stateless, scaled x N)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/quicklink/app/...
│       ├── link/               # shortener domain
│       ├── media/              # upload endpoint + enqueue
│       ├── redirect/           # GET /{code}
│       ├── ratelimit/
│       └── config/
│   └── src/main/resources/db/migration/   # Flyway V1__..., V2__...
│
├── worker/                     # Spring Boot: RabbitMQ consumer, does compression
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/quicklink/worker/...
│
└── edge-lb/                    # plain Java L7 load balancer (no Spring)
    ├── pom.xml
    ├── Dockerfile
    └── src/main/java/com/quicklink/lb/...
```

`app-service` and `worker` share the same Postgres and MinIO; `worker` can start as a Spring profile _inside_ `app-service` if you want to move faster, then split into its own module once it works. Splitting it out is what lets you scale workers independently — which is the whole reason a queue exists.

---

## 4. Prerequisites

- **JDK 21** (`sdkman` makes version management easy: `sdk install java 21-tem`)
- **Docker Desktop** (you already have this)
- **Maven 3.9+** (or use the Maven wrapper `./mvnw` that Spring Initializr generates)
- **IntelliJ IDEA** (Community is fine) — best Java + Spring experience
- A REST client: `curl`, Postman, or IntelliJ's `.http` scratch files

---

## 5. Phased development plan

Each stage produces something you can run and demo. Build the vertical slice first, then thicken it. If you only finish Stages 1–3 you already have a complete, defensible project — treat media processing as the ambitious bonus.

---

### Stage 0 — Scaffolding & environment (½ day)

**Goal:** an empty-but-running app that boots and connects to Postgres.

1. Generate the app module from **[start.spring.io](https://start.spring.io)**:
   - Project: Maven · Language: Java · Spring Boot: latest 3.x · Java: 21
   - Group `com.quicklink`, Artifact `app-service`, packaging Jar
   - Dependencies: **Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Validation, Spring Boot Actuator, Lombok** (optional — records cover a lot)
2. Unzip it, `git init`, commit. Add a `.gitignore` (target/, .idea/, \*.iml, .env).
3. Convert to a **parent multi-module** project: create a top-level `pom.xml` with `<packaging>pom</packaging>` and `<modules><module>app-service</module></modules>`. Move the generated project under `app-service/`.
4. Write a minimal `docker-compose.yml` with just Postgres:
   ```yaml
   services:
     postgres:
       image: postgres:16
       environment:
         POSTGRES_DB: quicklink
         POSTGRES_USER: quicklink
         POSTGRES_PASSWORD: quicklink
       ports: ["5434:5432"]
       volumes: [pgdata:/var/lib/postgresql/data]
   volumes: { pgdata: {} }
   ```
5. Point `application.yml` at Postgres, set `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns the schema, not Hibernate).
6. `docker compose up -d postgres`, then `./mvnw spring-boot:run`. Hit `http://localhost:8080/actuator/health` → expect `{"status":"UP"}`.

**Verify:** app boots, Actuator health is UP, Postgres connection succeeds.

---

### Stage 1 — URL shortener core, single instance (2–3 days)

**Goal:** POST a URL, get a short code; GET the code, receive a redirect.

1. **Flyway migration** `V1__init.sql`:
   ```sql
   CREATE TABLE link (
     id           BIGSERIAL PRIMARY KEY,
     short_code   VARCHAR(16) NOT NULL UNIQUE,
     target_type  VARCHAR(8)  NOT NULL,          -- 'URL' or 'MEDIA'
     target_url   TEXT,                           -- when target_type = 'URL'
     media_asset_id BIGINT,                       -- when target_type = 'MEDIA' (Stage 4)
     created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
     expires_at   TIMESTAMPTZ
   );
   CREATE INDEX idx_link_short_code ON link (short_code);   -- fast redirect lookups
   ```
   > That index on `short_code` is the single biggest performance lever for a read-heavy service — call it out in your README.
2. **JPA entity** `Link` + **repository** `LinkRepository extends JpaRepository<Link, Long>` with `Optional<Link> findByShortCode(String code)`.
3. **Base62 encoder** — the interview-favored, collision-free approach is _counter → base62_. Use a Postgres sequence (or the `BIGSERIAL` id) as the counter, then encode it:
   ```java
   public final class Base62 {
     private static final String ALPHABET =
       "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
     public static String encode(long n) {
       if (n == 0) return "0";
       StringBuilder sb = new StringBuilder();
       while (n > 0) { sb.append(ALPHABET.charAt((int)(n % 62))); n /= 62; }
       return sb.reverse().toString();
     }
   }
   ```
   Flow: insert the row to get the auto-generated `id`, then `short_code = Base62.encode(id)` and update. (Because ids are unique, codes never collide — no retry loop needed. Know the tradeoff: sequential codes are enumerable; mention XOR-with-a-secret before encoding as the mitigation.)
4. **Service layer:** `createLink(longUrl, expiresAt)` and `resolve(code)`. Validate the URL (`@URL` / `commons-validator`); reject malformed input with `400`.
5. **Controllers:**
   - `POST /api/links` body `{ "longUrl": "...", "expiresAt": "..." }` → `201` `{ "shortCode", "shortUrl" }`
   - `GET /{shortCode}` → look up, check expiry, return **302** (temporary — preserves click analytics; know why you'd pick 301 for the pure load-reduction case)
6. **Tests:** unit-test `Base62`; write a **Testcontainers** integration test that boots real Postgres, creates a link, and follows the redirect via MockMvc.
7. Manual smoke test:
   ```bash
   curl -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
        -d '{"longUrl":"https://example.com/some/very/long/path"}'
   curl -i localhost:8080/aB3xZ      # expect HTTP/1.1 302 with Location header
   ```

**Deliverable:** a working URL shortener. This alone is the canonical interview project.

---

### Stage 2 — Caching, rate limiting, analytics (2–3 days)

**Goal:** make the read path fast and the write path abuse-resistant.

1. Add **Redis** to compose:
   ```yaml
   redis:
     image: redis:7
     ports: ["6379:6379"]
   ```
   Add the `spring-boot-starter-data-redis` dependency.
2. **Cache-aside on redirects.** On `GET /{code}`, check Redis first (`code → targetUrl`); on miss, read Postgres and populate Redis with a TTL. Either use Spring Cache (`@Cacheable`) or a manual `RedisTemplate` for explicit control. Invalidate/expire the cache when a link expires.
3. **Rate limiting** with **Bucket4j + Redis** on `POST /api/links` (e.g. N creates per minute per IP). Because the bucket lives in Redis, the limit holds _across all app instances_ — which matters once you scale in Stage 3.
4. **Analytics without hammering the DB:** `INCR` a Redis counter per short code on each redirect; a `@Scheduled` job flushes counters to a `link_stats` table every minute. Add `GET /api/links/{code}/stats`.
5. Tests: cache hit/miss behavior, rate-limit returns `429`, counter flush.

**Deliverable:** a fast, abuse-resistant shortener. You can now talk credibly about cache-aside, TTLs, and distributed rate limiting.

---

### Stage 3 — Statelessness + your custom load balancer (4–6 days)

**Goal:** run several app instances and route to them through a load balancer you wrote. This is the backbone that unifies the project.

1. **Make the app provably stateless.** No in-memory session, no local counters, no local file writes — everything in Postgres/Redis. Add a `Dockerfile` to `app-service` and confirm you can run several copies:
   ```bash
   docker compose up -d --scale app=3    # (after wiring 'app' into compose)
   ```
2. **Build `edge-lb`** as a plain-Java Maven module (no Spring). Recommended shape: JDK `HttpServer` for the listener, `java.net.http.HttpClient` to forward, and a **virtual-thread-per-request** executor.
   - **Backend pool:** a config list of `host:port` for the app instances.
   - **Reverse proxy:** on each incoming request, pick a healthy backend, copy method + path + headers + body, forward, then stream the response back to the client.
     ```java
     // sketch of the proxy handler
     HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
     server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
     server.createContext("/", exchange -> {
         Backend b = pool.next();                 // load-balancing decision
         if (b == null) { exchange.sendResponseHeaders(503, -1); return; }
         b.inFlight().incrementAndGet();
         try {
             HttpResponse<byte[]> resp = forward(client, b, exchange);
             exchange.sendResponseHeaders(resp.statusCode(), resp.body().length);
             exchange.getResponseBody().write(resp.body());
         } finally { b.inFlight().decrementAndGet(); exchange.close(); }
     });
     ```
   - **Algorithm — start with round-robin:** an `AtomicInteger` index modulo the list of _healthy_ backends.
   - **Then add least-connections:** track an `AtomicInteger inFlight` per backend (see sketch) and pick the healthy backend with the smallest value. This is a great before/after to show in the README.
   - **Health checks:** a `ScheduledExecutorService` that every ~5s does `GET http://backend/actuator/health` on each backend; `200` → mark healthy, anything else → mark unhealthy and stop routing to it. When it passes again, put it back in rotation.
   - **Failover:** the selection step only ever considers healthy backends, so a dead instance is skipped automatically.
3. Put the LB in compose in front of the app instances; point your `curl`/browser at the LB's port.
4. **Demo the payoff:** hit the LB repeatedly and watch responses rotate across instances (add an instance-id header to prove it); then `docker kill` one instance and confirm requests keep succeeding with zero client-visible errors; bring it back and watch it rejoin.

**Deliverable:** a horizontally scaled service behind a load balancer you built. In your README, be explicit: _"I built this to understand what nginx/HAProxy do for me"_ — that framing turns it into a strength.

---

### Stage 4 — Media upload + async compression pipeline (5–7 days)

**Goal:** a short code can point to an uploaded file that's compressed in the background and served from object storage.

1. Add **RabbitMQ** and **MinIO** to compose:
   ```yaml
   rabbitmq:
     image: rabbitmq:3-management
     ports: ["5672:5672", "15672:15672"] # 15672 = web UI
   minio:
     image: minio/minio
     command: server /data --console-address ":9001"
     environment:
       MINIO_ROOT_USER: minio
       MINIO_ROOT_PASSWORD: minio123
     ports: ["9000:9000", "9001:9001"]
   ```
   Create two buckets on startup: `originals` and `processed`.
2. **`media_asset` table** (Flyway `V2__media.sql`):
   ```sql
   CREATE TABLE media_asset (
     id             BIGSERIAL PRIMARY KEY,
     status         VARCHAR(12) NOT NULL,   -- PENDING | PROCESSING | READY | FAILED
     content_type   VARCHAR(64) NOT NULL,
     original_key   TEXT NOT NULL,
     processed_key  TEXT,
     original_bytes BIGINT,
     processed_bytes BIGINT,
     error_message  TEXT,
     created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
     updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   ```
3. **Upload endpoint** (`POST /api/media`, multipart):
   - Stream the file straight to MinIO `originals/{uuid}` (don't buffer big files in memory).
   - Insert `media_asset` with status `PENDING`; create a `link` row (`target_type = 'MEDIA'`).
   - **Publish a job** `{ assetId, originalKey }` to RabbitMQ queue `media.compress`.
   - Return **202 Accepted** with `{ shortCode, status: "PENDING" }` immediately. The HTTP request stays short-lived; the heavy work happens off the request thread.
4. **Worker module** (`@RabbitListener` on `media.compress`):
   - Set status `PROCESSING`. Download the original from MinIO.
   - Compress with **Thumbnailator** (e.g. cap dimensions and re-encode at a target quality).
   - Upload the result to `processed/{uuid}`, set status `READY`, record byte sizes.
   - **Idempotency:** if the asset is already `READY`, no-op; re-running must be safe (overwrite the processed key). This is the idempotent-worker pattern.
   - **Retries + dead-letter queue:** configure Spring AMQP retry; after N failures route the message to `media.compress.dlq` and set status `FAILED` with `error_message`. Never let one poison file wedge the queue.
5. **Serving:** `GET /{shortCode}` for a media link → if `READY`, generate a **presigned MinIO URL** (via `S3Presigner`) and `302` to it; if still `PENDING/PROCESSING`, return `202` or a small "still processing" page. Add `GET /api/media/{code}/status`.
6. **Scale workers independently** to prove the point of the queue:
   ```bash
   docker compose up -d --scale worker=3
   ```
7. Tests: Testcontainers for RabbitMQ **and** MinIO; assert an upload transitions PENDING → READY and that a deliberately broken file lands in the DLQ as FAILED.

**Deliverable:** the full async media pipeline — producer/consumer, worker pool, object storage, retries, DLQ, idempotency.

---

### Stage 5 — Observability, docs, polish (3–5 days)

**Goal:** make it legible to an interviewer and pleasant to run.

1. **Metrics:** expose `/actuator/prometheus` (Micrometer), add Prometheus + a small Grafana dashboard to compose. Graph redirect latency, cache hit rate, queue depth, and worker throughput — the async pipeline becomes _visible_.
2. **API docs:** add springdoc-openapi → Swagger UI. Free, and it looks professional.
3. **Structured logging** with a correlation/request id propagated from the LB through the app and into the worker, so you can trace one upload end to end.
4. **CI:** GitHub Actions running `./mvnw verify` (Testcontainers works in Actions) on every push.
5. **README — this is what makes it a portfolio piece, not a code dump.** Include: the architecture diagram, one-command run (`docker compose up`), the API surface, and a short **"Design decisions & tradeoffs"** section (why counter+base62, why 302, cache-aside, why you built your own LB, replicas-vs-shards, idempotency, DLQ). Interviewers read this section first.
6. Optional: a minimal React or plain-HTML frontend (paste a link or drag a file, see the short URL and, for media, a live status that flips to READY).

**Deliverable:** a clean, observable, documented system you can demo in one command and defend in an interview.

---

## 6. API surface (summary)

| Method | Path                            | Purpose                                                      |
| ------ | ------------------------------- | ------------------------------------------------------------ |
| POST   | `/api/links`                    | Create a short link for an external URL                      |
| POST   | `/api/media`                    | Upload a file → returns short code, `202`, async compression |
| GET    | `/{shortCode}`                  | Redirect (link) or serve/redirect to processed media         |
| GET    | `/api/links/{shortCode}/stats`  | Click analytics                                              |
| GET    | `/api/media/{shortCode}/status` | Compression status                                           |
| GET    | `/actuator/health`              | Health — **polled by the load balancer**                     |
| GET    | `/actuator/prometheus`          | Metrics scrape endpoint                                      |
| GET    | `/swagger-ui.html`              | API docs                                                     |

---

## 7. Testing strategy

- **Unit:** pure logic (Base62, load-balancer selection algorithms, rate-limit math) with plain JUnit + Mockito.
- **Integration (Testcontainers):** real Postgres, Redis, RabbitMQ, MinIO spun up per test class. Cover: create→redirect, cache hit/miss, rate-limit `429`, upload PENDING→READY, poison file→DLQ→FAILED.
- **Load-balancer behavior:** start two stub backends, assert round-robin distribution, then kill one and assert requests still succeed (failover), then revive and assert it rejoins.

---

## 8. Stretch goals (only after the core is solid)

- **Consistent hashing** in the LB (sticky routing) — pairs naturally with the hashing you already understand.
- **Database sharding** of the `link` table by short-code hash, with a shard router — the perfect place to show you understand replica-vs-shard.
- **Video** compression via an ffmpeg wrapper (heavier CPU, longer jobs — good justification for the queue).
- **Kubernetes:** deploy the same containers to a local `kind`/`minikube` cluster and let a real `Service` load-balance — then contrast it with your hand-built LB.

---

## 9. Suggested timeline

Roughly **4–6 weeks** part-time. Stages 1–3 (~2–3 weeks) give you a complete, defensible project; Stages 4–5 (~2–3 weeks) make it substantial. Commit at the end of every stage so your Git history tells the story of the build.
