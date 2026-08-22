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

| Concern          | Choice | Why |
| ---------------- | ------ | --- |
| Language         | **Java 21** compile target | Records, pattern matching, and stable **virtual threads** (great for the load balancer). The parent POM sets `<java.version>21</java.version>`; building on a newer JDK (24, 25) is fine — Maven compiles with `--release 21`. |
| Build            | **Maven, multi-module** | One repo, several deployables (app, worker, load balancer). Ubiquitous in Java shops. Gradle is a fine alternative. |
| App framework    | **Spring Boot 4.1.x** | Web, JPA, Redis, AMQP, Actuator all first-party. Huge resume signal. **Boot 4 renamed many starters — read §2.1 before copying any tutorial's dependencies.** |
| REST             | **Spring Web MVC** (`spring-boot-starter-webmvc`) | Standard, well understood, easy to test with MockMvc. |
| Database         | **PostgreSQL 16** | URL mappings + media metadata. Relational is a defensible, explainable choice. |
| Persistence      | **Spring Data JPA (Hibernate 7.x)** | Entities, repositories, less boilerplate. |
| Migrations       | **Flyway 12.x** (`spring-boot-starter-flyway`) | Versioned, repeatable schema. Interviewers like seeing this. In Boot 4 Flyway has its own starter — the JPA starter no longer pulls it in. |
| Cache            | **Redis 7 (Lettuce client)** | Cache-aside on redirects, click counters, rate-limit buckets. |
| Rate limiting    | **Bucket4j + Redis** | Distributed token bucket that works across instances. Version-check against Boot 4 when you get there (§2.1). |
| Queue            | **RabbitMQ + Spring AMQP** (`spring-boot-starter-amqp`) | Media compression jobs, retries, dead-letter queue. |
| Object storage   | **MinIO** (S3-compatible, self-hosted) + **AWS SDK v2 S3 client** | Learn the S3 API locally with no cloud bill; swap to real S3 later by changing config. |
| Image processing | **Thumbnailator** | Simple, dependable image resize/recompress. (ffmpeg for video is a stretch goal.) |
| Load balancer    | **Plain Java** (JDK `HttpServer` + `java.net.http.HttpClient`, virtual threads) — **no Spring** | The point is to see the mechanism. Keep it thin. |
| Containers       | **Docker + Docker Compose** | Postgres, Redis, RabbitMQ, MinIO, N app instances, the LB — all wired together. |
| Testing          | **JUnit 5, Testcontainers 2.x, Mockito, MockMvc** | Testcontainers spins up real Postgres/Redis/Rabbit/MinIO in tests — a strong signal. **2.x renamed its modules** (§2.1). |
| API docs         | **springdoc-openapi** | Free Swagger UI at `/swagger-ui.html`. Needs a Boot 4-compatible release (§2.1). |
| Observability    | **Actuator + Micrometer + Prometheus** (+ Grafana, optional) | `/actuator/health` is what your LB polls; metrics make the async pipeline visible. |
| CI               | **GitHub Actions** | Build + test on every push. |

### 2.1 Spring Boot 4 vs 3.x — read this before copying any tutorial

This project runs **Spring Boot 4.1** (Spring Framework 7.0). Virtually every tutorial, StackOverflow answer, and blog post you will find targets Boot 3.x. When a dependency won't resolve or an import is missing, this table is almost always why.

| Boot 3.x (what you'll read online) | Boot 4.x (what this project uses) |
| --- | --- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Flyway bundled with the JPA starter | `spring-boot-starter-flyway` (explicit) |
| `spring-boot-starter-test` — one jar for everything | split per slice: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-actuator-test`, `spring-boot-starter-flyway-test` |
| Jackson 2 — `com.fasterxml.jackson.databind` | **Jackson 3** — databind moved to `tools.jackson.databind` (annotations stay at `com.fasterxml.jackson.annotation`) |
| Testcontainers 1.x — `org.testcontainers:postgresql`, `:junit-jupiter` | Testcontainers **2.x** — `org.testcontainers:testcontainers-postgresql`, `:testcontainers-junit-jupiter` |
| Spring Framework 6 | Spring Framework 7 |

**Unchanged**, despite what you might expect: `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`.

All of the above are version-managed by `spring-boot-starter-parent`, so **never write a `<version>` tag** for them.

**Third-party libraries to version-check as you reach them** — these lag major Spring releases: **Bucket4j** (Stage 2) and **springdoc-openapi** (Stage 5). If no Boot 4-compatible release exists yet, the fallbacks are a hand-rolled Redis token bucket and a static `openapi.yaml`. Both are perfectly good, and being able to explain *why* you hand-rolled it is itself a strong signal.

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

- **JDK 21 or newer** (`sdkman` makes version management easy: `sdk install java 21-tem`). The build targets 21 via `--release`, so a newer JDK on your machine is fine.
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
   - Project: Maven · Language: Java · Spring Boot: **latest 4.x** · Java: 21
   - Group `com.quicklink`, Artifact `app-service`, packaging Jar
   - Dependencies: **Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Validation, Spring Boot Actuator, Lombok**
   - Initializr emits Boot 4 artifact names (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, the split `*-test` starters). **Don't "correct" them** to the 3.x names you see in tutorials — see §2.1.
   - Keep **Lombok**: JPA entities can't be records (they need a no-arg constructor and mutable fields), so it genuinely earns its place.
2. Unzip it, `git init`, commit. Add a `.gitignore` — Initializr's covers `target/` and IDE files; add **`.DS_Store`** on macOS and `.env`. Do **commit** `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` (the wrapper is how CI builds without a Maven install); the wrapper `.jar` stays ignored.
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
5. Point `application.yaml` at Postgres and set `spring.jpa.hibernate.ddl-auto: validate` (Flyway owns the schema, not Hibernate). `validate` makes Hibernate **refuse to boot** when an entity and its table disagree — that's the point, but the failure is loud: `Schema-validation: missing column [x]` means fix the entity or add a migration.
   > Use env-var placeholders with local defaults so no real credential is ever committed: `password: ${DB_PASSWORD:quicklink}`.
6. `docker compose up -d postgres`, then `./mvnw spring-boot:run`. Hit `http://localhost:8081/actuator/health` → expect `{"status":"UP"}`.
   > **Ports:** this project sets `server.port: 8081` (8080 is a busy default). Every `curl` below uses 8081 — change the yaml if you'd rather use 8080.

**Verify:** app boots, Actuator health is UP, Postgres connection succeeds.

---

### Stage 1 — URL shortener core, single instance (2–3 days)

**Goal:** POST a URL, get a short code; GET the code, receive a redirect.

1. **Flyway migration** at `app-service/src/main/resources/db/migration/V1__init.sql` — note the **double** underscore; with a single one Flyway silently ignores the file.
   ```sql
   -- An explicit sequence, NOT bigserial. Step 3 explains why this choice matters.
   -- Starting past 62^4 (14,776,336) guarantees every short code is >= 5 characters,
   -- instead of the first links being "1", "2", "3".
   CREATE SEQUENCE link_id_seq START WITH 14776336 INCREMENT BY 1;

   CREATE TABLE link (
     id           BIGINT PRIMARY KEY DEFAULT nextval('link_id_seq'),
     short_code   VARCHAR(16) NOT NULL UNIQUE,
     target_type  VARCHAR(8)  NOT NULL,          -- 'URL' or 'MEDIA'
     target_url   TEXT,                           -- when target_type = 'URL'
     media_asset_id BIGINT,                       -- when target_type = 'MEDIA' (Stage 4)
     created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
     expires_at   TIMESTAMPTZ
   );
   ```
   > **Deliberately no `CREATE INDEX` on `short_code`.** In Postgres a `UNIQUE` constraint *is* implemented as a unique B-tree index, so adding an explicit index would build and maintain a second, identical one — pure write overhead for zero read gain. The README line to write is: _"the unique constraint on `short_code` gives me the redirect lookup index for free, so resolving a code is a single index probe."_ That phrasing shows you understand the mechanism rather than having copied it — which is the whole point of building this.
   >
   > **Never edit a migration once it has been applied.** Flyway checksums applied files and will refuse to start. Add `V2__...` instead. If you need to reset while experimenting: `docker compose down -v` to drop the volume.
2. **JPA entity** `Link` + **repository** `LinkRepository extends JpaRepository<Link, Long>` declaring `Optional<Link> findByShortCode(String code)`. You write **no implementation** — Spring Data parses the method name and generates the query at startup, so a misspelled field fails at boot rather than at runtime.

   Mapping details that will bite you otherwise:
   - `target_type` → `@Enumerated(EnumType.STRING)`. The default is `ORDINAL`, which stores `0`/`1`; reordering the enum later silently corrupts every existing row. Never use ordinal.
   - `TIMESTAMPTZ` → `java.time.Instant`, not `LocalDateTime` (which carries no timezone).
   - `created_at` has a DB default, so mark it `@Column(insertable = false, updatable = false)` and let Postgres fill it, or set it in Java — pick one, not both.
3. **Base62 encoder** — the interview-favored, collision-free approach is _counter → base62_:
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
   **You must obtain the id _before_ the INSERT.** The obvious-sounding flow — "insert the row, read back the generated id, then `UPDATE` it with the short code" — **cannot work here**: `short_code` is `NOT NULL`, so that first insert fails before any id exists. The fix is choosing the right id-generation strategy:

   | Strategy | When the id exists | Consequence |
   | --- | --- | --- |
   | `IDENTITY` (what `bigserial` implies) | *during* the INSERT | Hibernate can't know it in advance, forcing INSERT-then-UPDATE — and it silently disables JDBC batching |
   | **`SEQUENCE`** (use this) | *before* the INSERT, via `nextval()` | the id is available at `persist()`, so you compute the code in memory and send **one** fully-populated INSERT |

   So map the id against the sequence created in step 1:
   ```java
   @Id
   @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "link_seq")
   @SequenceGenerator(name = "link_seq", sequenceName = "link_id_seq", allocationSize = 1)
   private Long id;
   ```
   > **`allocationSize` gotcha:** Hibernate's default is **50**, which assumes the database sequence is declared `INCREMENT BY 50`. Yours increments by 1, so leaving the default produces id collisions. Set `allocationSize = 1`; if you later want the batching win, raise the annotation and the SQL together.

   Inside one `@Transactional` service method the flow becomes:
   ```
   repository.save(link)                            // persist() -> nextval(), id assigned; INSERT not yet sent
   link.setShortCode(Base62.encode(link.getId()));  // the id is available now
   // transaction commits -> dirty-checked flush -> a single INSERT with short_code populated
   ```
   Because ids are unique, codes never collide — no retry loop needed. Know the tradeoff: sequential codes are **enumerable** (`aB3xZ` implies `aB3xY` probably exists). The standard mitigation is XOR-ing the id with a secret constant before encoding — two lines, and a good thing to have an opinion about in an interview.
4. **Service layer:** `createLink(longUrl, expiresAt)` and `resolve(code)`. Put `@Transactional` **on the service, not the controller** — that boundary is what makes the dirty-checking flow in step 3 work. Validate the URL with Jakarta Validation (`@URL` from Hibernate Validator, via `spring-boot-starter-validation`) and reject malformed input with `400`.
   > `spring-boot-starter-validation` is **not** in the generated pom — add it now, it's needed for steps 4 and 5.
5. **Controllers:**
   - `POST /api/links` body `{ "longUrl": "...", "expiresAt": "..." }` → `201` `{ "shortCode", "shortUrl" }`. Use `@Valid @RequestBody` so constraint violations turn into `400` automatically. A record is the right shape for the request/response DTOs (unlike the entity).
   - `GET /{shortCode}` → look up, check expiry, return **302** — `ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()`. Temporary is right because it preserves click analytics; know why you'd pick 301 for the pure load-reduction case.
   - **Constrain that catch-all.** A bare `@GetMapping("/{shortCode}")` sits at the root and will swallow `/favicon.ico`, `/robots.txt`, and every mistyped path. Use a regex instead: `@GetMapping("/{shortCode:[0-9a-zA-Z]{5,16}}")`. (`/api/**` still wins over it — Spring prefers the more specific pattern — and `/actuator/**` is mapped separately.)
6. **Tests:**
   - Unit-test `Base62` with **plain JUnit** — it's a pure static function, so no `@SpringBootTest` (that would boot the whole app and require Postgres for nothing).
   - Integration test with **Testcontainers 2.x**: add `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter`, and Boot's `spring-boot-testcontainers`. Mind the 2.x renaming (§2.1) — tutorials will tell you the artifact is just `postgresql`.
   - Annotate the container field with **`@ServiceConnection`** and Boot wires the datasource URL and credentials automatically. Older guides use `@DynamicPropertySource` with three `registry.add(...)` calls; you don't need that.
   - Assert the whole slice: create a link, then follow the redirect with MockMvc and check the `Location` header. This test also proves your Flyway migration and entity agree.
7. Manual smoke test (port **8081** — see the note in Stage 0):
   ```bash
   curl -X POST localhost:8081/api/links -H 'Content-Type: application/json' \
        -d '{"longUrl":"https://example.com/some/very/long/path"}'
   curl -i localhost:8081/aB3xZ      # expect HTTP/1.1 302 with Location header
   ```

**Suggested build order** — bottom-up, so each piece is verifiable before anything depends on it: migration → boot the app (proves Flyway ran) → entity + repository → `Base62` + its unit test → service → controllers → integration test.

**Deliverable:** a working URL shortener. This alone is the canonical interview project.

---

### Stage 2 — Caching, rate limiting, analytics (2–3 days)

**Goal:** make the read path fast and the write path abuse-resistant.

1. Add **Redis** to compose:
   ```yaml
   redis:
     image: redis:7
     ports: ["6380:6379"]
   ```
   > Host port **6380**, not 6379 — you very likely already have another project's Redis on 6379, and the collision surfaces as a confusing connection failure. Set `spring.data.redis.port: 6380` to match. Inside the compose network other containers still reach it on 6379.

   Add the `spring-boot-starter-data-redis` dependency (name unchanged in Boot 4).
2. **Cache-aside on redirects.** On `GET /{code}`, check Redis first (`code → targetUrl`); on miss, read Postgres and populate Redis with a TTL. Either use Spring Cache (`@Cacheable`) or a manual `RedisTemplate` for explicit control. Invalidate/expire the cache when a link expires.
3. **Rate limiting** with **Bucket4j + Redis** on `POST /api/links` (e.g. N creates per minute per IP). Because the bucket lives in Redis, the limit holds _across all app instances_ — which matters once you scale in Stage 3.
   > **Version-check Bucket4j against Boot 4 / Spring Framework 7 before committing to it** (§2.1). If there's no compatible release, implement the token bucket yourself with a small Redis Lua script — ~20 lines, and it makes the algorithm something you can whiteboard rather than something a library did for you.
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

1. **Metrics:** expose `/actuator/prometheus` (Micrometer), add Prometheus + a small Grafana dashboard to compose. Graph redirect latency, cache hit rate, queue depth, and worker throughput — the async pipeline becomes _visible_. In Boot 4 metrics support lives in `spring-boot-starter-micrometer-metrics`; add that plus `micrometer-registry-prometheus`.
2. **API docs:** add springdoc-openapi → Swagger UI. Free, and it looks professional. **Check for a Boot 4-compatible release first** (§2.1) — the 2.x line targets Boot 3. If there isn't one, hand-write a static `openapi.yaml` and serve Swagger UI from it.
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
