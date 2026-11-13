# Quicklink

**Live at <https://quicklink-rv.northcentralus.cloudapp.azure.com>** — the architecture
visualiser below, running on the deployed stack: real S3, real SQS, no canned data.

A URL shortener and async media pipeline on **Spring Boot 4 / Java 21**, running as
three stateless app instances behind a **load balancer I wrote from scratch**, with a
pool of three workers compressing uploads off the request thread.

The shortening is the boring part. The project exists for everything around it:
cache-aside with negative caching, write-behind click counting, a rate limiter that
still holds when you add instances, leader election for a scheduled job, and a
producer/consumer pipeline with idempotency and a dead-letter queue.

I built the load balancer by hand to understand what nginx and HAProxy do for me.
Measuring it changed two of my design decisions — both are written up below.

**Where it runs.** One **Azure** VM carries the whole stack under Docker Compose — the load
balancer, three app instances, two workers (three locally), Postgres and Redis. Two things are rented from
**AWS**: **S3** holds the image buckets and **SQS** carries the compression jobs. The rule
was simple — anything whose mechanism is the point of the project is self-hosted, and the
two pure commodities are not. [Service-by-service breakdown below](#deploying).

---

## See it working

It is deployed at **<https://quicklink-rv.northcentralus.cloudapp.azure.com>**, so you can
press the buttons without cloning anything. The deployed copy talks to S3 and SQS behind
Caddy-terminated TLS; the local one below is the same code against SeaweedFS and RabbitMQ.

```bash
docker compose up -d --build        # Postgres, Redis, RabbitMQ, SeaweedFS,
                                    # 3 app instances, 3 workers, the load balancer
open http://localhost:8080
```

`http://localhost:8080` serves a **live architecture visualisation**. Press a button and
it animates a real request through the real system — there is no canned data:

| Button | What you watch happen |
|---|---|
| **Create a short link** | the load balancer picking an instance, a token leaving the Redis bucket, the row landing in Postgres |
| **Follow the link** | the `302`, and the click counter incrementing in Redis rather than the database |
| **Upload an image** | the request ending at `202 PENDING`, then the queue, a worker, both buckets, and the status flipping to `READY` with the real byte counts |
| **Burst 12 creates** | the shared bucket draining to zero and the last two requests getting `429` |

Which instance lit up comes from the `X-Instance-Id` response header. The token count is
`X-Rate-Limit-Remaining`. The compression ratio is read back from the status endpoint.
Everything on screen happened.

---

## Architecture

```
                    ┌──────────────┐
   you ───────────► │   edge-lb    │  :8080   plain Java, zero dependencies
                    │ round-robin  │          virtual thread per request
                    └──────┬───────┘          health checks + retry failover
                 ┌─────────┼─────────┐
            ┌────▼───┐ ┌───▼────┐ ┌──▼─────┐
            │ app-1  │ │ app-2  │ │ app-3  │  :8081, stateless, not published
            └────┬───┘ └───┬────┘ └───┬────┘
         ┌───────┴─────────┴──────────┴────────┐
         │                 │                  │
    ┌────▼─────┐    ┌──────▼─────┐      ┌─────▼──────┐
    │  Redis   │    │  Postgres  │      │ SeaweedFS  │
    │ cache    │    │ links      │      │ originals  │
    │ buckets  │───►│ stats      │      │ processed  │
    │ counters │ ▲  │ assets     │      └─────▲──────┘
    └──────────┘ │  └────────────┘            │
                 │   write-behind             │
                 │   flush (60s)         ┌────┴─────┐
                            ┌──────────┐ │ worker×3 │
                            │ RabbitMQ ├─► competing│
                            │ .compress│ │ consumers│
                            └────┬─────┘ └──────────┘
                                 └──► media.compress.dlq
```

---

## API

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/api/links` | `{longUrl, expiresAt?}` → `201 {shortCode, shortUrl}`. Validated, rate-limited per client. |
| `POST` | `/api/media` | multipart → `202 {shortCode, status:"PENDING"}`. Returns before any compression happens. |
| `GET` | `/{shortCode}` | URL link → `302`. Media → `302` to a presigned URL (`READY`), `202` (`PENDING`/`PROCESSING`), or `410 Gone` (`FAILED`). |
| `GET` | `/api/links/{shortCode}/stats` | `{shortCode, clicks}` — persisted plus whatever is still pending in Redis. |
| `GET` | `/api/media/{shortCode}/status` | status, byte counts, and the error message if it failed. |
| `GET` | `/actuator/health` | aggregate health, including Postgres and Redis. For monitoring. |
| `GET` | `/actuator/health/readiness` | what the load balancer polls — deliberately excludes the datastores. |

Short codes are 5–16 alphanumerics, enforced by the route pattern, so `/{shortCode}` can
never shadow `/api/…`, `/actuator/…` or the static page.

---

## How it works

### Short codes

A Postgres sequence, Base62-encoded. No randomness, so **no collision check and no retry
loop** — uniqueness comes from the counter and base conversion is a bijection. The
sequence starts at 62⁴, which guarantees every code is at least five characters.

There are two sequences, not one, and the reason is specific. `short_code` is `NOT NULL`,
so the code must exist *before* the `INSERT` — but `repository.save()` does not send SQL;
it captures the entity's values and queues the insert, so anything set afterwards becomes a
separate `UPDATE` that arrives too late. The primary key comes from a sequence Hibernate
calls privately inside `save()`, so it is unavailable beforehand. A second sequence that the
service calls itself solves it, and decouples the public identifier from the primary key.

The tradeoff: codes are **enumerable**. Rate limiting is the mitigation, not obfuscation —
XOR-ing the counter only hides creation order from someone who has not seen a few pairs.

### The redirect path

Redis first, Postgres only on a miss. TTL is capped at an hour and clamped to the link's own
expiry, so a cached entry can never outlive the link. An unknown code caches a `__missing__`
sentinel for 30 seconds — without that, a flood of requests for nonexistent codes is a flood
of database reads, which is exactly the traffic a cache should absorb.

Media links are **deliberately not cached**: their status changes while the worker runs, and
a presigned URL expires. Presigning is local HMAC work, so regenerating per request is cheap.

### Click counting

Counting on the redirect path would put a write on every hit and make the most popular link
the slowest, so it is **write-behind**: `INCR` in Redis plus a dirty-set entry, and a
scheduled job folds the deltas into Postgres every 60 seconds with
`INSERT … ON CONFLICT DO UPDATE`.

Two details that are easy to get wrong. The drain uses `GETDEL`, not `GET` then `DEL` —
anything incrementing between two separate calls would be lost silently. And it removes the
code from the dirty set *before* reading the counter, so a click arriving mid-flush re-marks
the code instead of vanishing.

### Rate limiting

A Bucket4j token bucket (10 per 60 s per client) held **in Redis**, guarding only
`POST /api/links` — redirects stay unmetered. The app holds the *rules*; Redis holds the
*counts*, so adding instances does not multiply the limit.

Behind the load balancer the client IP needs `X-Forwarded-For`, which `edge-lb` sets and
`server.forward-headers-strategy: framework` makes Spring trust. That setting is only safe
because the app instances publish no host ports — the proxy is the only way in. Reachable
instances plus a trusted header means any client can spoof its way to a fresh bucket.

### The load balancer

`edge-lb` is plain Java with **no dependencies at all** — `HttpServer` to listen,
`HttpClient` to forward, one virtual thread per request. A proxy is almost pure I/O waiting,
which is precisely the workload virtual threads exist for; it means the forwarding code is
ordinary blocking code rather than callbacks.

It health-checks `/actuator/health/readiness` every 5 s, and a failed forward marks that
backend unhealthy immediately and retries another. Two non-obvious requirements:
`followRedirects(NEVER)`, because this app's whole purpose is returning `302`s and a client
that chased them would hand the caller someone else's page; and filtering hop-by-hop headers,
some of which `HttpClient` refuses to let you set at all.

### The async pipeline

An upload streams to object storage, writes a `PENDING` row, and returns `202` in about
50 ms. The job is published to RabbitMQ **after the transaction commits**. Postgres and
RabbitMQ share no transaction, so this is a choice between failure modes: publishing inside
the transaction risks the message being delivered and then the transaction rolling back,
handing a worker an asset id that does not exist. Publishing after commit leaves only a lost
message, which strands the asset visibly `PENDING` and is safe to re-drive.

The worker is idempotent because **at-least-once delivery means duplicates are normal
operation, not a bug**: already `READY` means ack and skip. Permanent failures (an undecodable
file) throw `AmqpRejectAndDontRequeueException` and skip the retries entirely; transient ones
get backed-off retries and then the dead-letter queue, where a listener records `FAILED`.
Without `default-requeue-rejected: false` an exhausted message is requeued forever and one
poison file wedges the queue for everything behind it.

The two services share a **JSON contract, not a Java class**. The producer stamps a
`__TypeId__` header naming a class the worker does not have; the worker sets
`TypePrecedence.INFERRED` so its own listener signature wins.

**The broker is swappable.** `MediaJobQueue` has a single method and two implementations,
chosen by `app.queue`: RabbitMQ (default) or Amazon SQS. The unused side's beans are never
created, so the SQS deployment needs no broker running anywhere and the Rabbit deployment
builds no AWS client. Implementing it twice is what showed me how differently the two think:

| | RabbitMQ | SQS |
|---|---|---|
| Who creates the queue | the application, at startup | Terraform; the app never declares topology |
| Delivery | the broker pushes to a listener | the consumer long-polls, 20 s at a time |
| Ack | the channel acks when the listener returns | `DeleteMessage`. **Not deleting is the retry** |
| Retries | `listener.simple.retry`, configured in the app | the queue's visibility timeout; the app has no say |
| Dead-lettering | a DLX, a binding, and `default-requeue-rejected: false` | a redrive policy with `maxReceiveCount` |
| Deadline on the handler | none | the visibility timeout: slower work gets a second consumer |

That last row is why the worker's idempotency check matters more on SQS than on Rabbit, and
the SQS test pins all of it against ElasticMQ — including a job that fails until the redrive
policy moves it to the dead-letter queue, with no broker container in the test at all.

---

## Design decisions & tradeoffs

Where I measured something, the number is here. Two of these went against my first instinct.

**Least-connections is worse than round-robin here — so it is off.** I implemented both. On
this workload every backend ties at zero in-flight and `min()` deterministically returns the
first, so least-connections degenerates to "always pick the first":

| strategy | 30 sequential requests | 30 concurrent |
|---|---|---|
| round-robin | 10 / 10 / 10 | ~even |
| least-connections | **30 / 0 / 0** | 8 / 7 / 15 |

Both stay behind a config switch. Least-connections earns its keep when request *durations*
vary, which this workload does not have.

**Health checks alone do not give you failover.** The obvious design — only route to backends
the health checker says are up — leaves a window equal to the check interval. Killing one of
three instances produced **20 failures out of 60**, because all 60 requests completed inside
one 5-second interval. Marking a backend unhealthy on the *first* failed forward and retrying
another took the same test to **90/90**. The periodic checker's real job is putting it back.

**The rate limit is genuinely global.** Twelve creates round-robined across three instances:
the token count fell 9, 8, 7 … 0 and the last two got `429`, against a single Redis key. An
in-memory bucket would have allowed all twelve, and the effective limit would have been 30.

**Scheduled work needs leader election.** `@Scheduled` fires on every instance, so three
copies raced to drain the same counters. A `SET key NX EX` lock makes exactly one win per
cycle. Over two cycles two *different* instances won, and the click total was exactly right —
no double counting, nothing lost.

**Identity generation: both strategies, opposite reasons.** `link` uses `SEQUENCE` because the
short code is derived from the id and must be in the same `INSERT`. `media_asset` uses
`IDENTITY` because its id is only needed *after* the insert, to point the link at it and to
put in the queue message.

**Multi-stage Docker build.** The final image is ~600 MB (a JRE plus the jar) instead of
~1.2 GB, and contains no compiler, no Maven, no source, and none of the 250 MB dependency
cache.

### Known limitations

- **Object storage is SeaweedFS, not MinIO.** MinIO's community images are no longer publicly
  pullable and LocalStack's current image requires a licence. The application code is
  unchanged either way — AWS SDK v2 against a custom endpoint.
- **Uploads are disk-buffered, not streamed end to end.** Spring writes the multipart to a
  temp file before the handler runs, so the file stays off the heap but is not a true stream.
- **The dead-letter listener records `FAILED` but nothing re-drives it.** A failed asset needs
  manual intervention.
- **Codes are enumerable** (see above).
- **No auth.** Anyone who can reach the API can create links and upload files.

---

## Deploying

The deployed topology is **one small VM running Docker Compose, with object storage and the
queue rented from AWS**:

```
  browser ──► VM (Azure for Students, 2 vCPU / 4 GB ARM)
              └─ compose: edge-lb, 3 app, 2 worker, Postgres, Redis
                                │
                                ▼
                 AWS: S3 (originals + processed) · SQS (+ DLQ)
```

### What each provider actually does

| Where | Service | Its job here |
|---|---|---|
| **Azure** | Virtual Machine — `Standard_B2pls_v2`, 2 vCPU / 4 GB, ARM64, Ubuntu 24.04 | runs every container: edge-lb on `:80`, 3 app instances, 2 workers, Postgres, Redis |
| **Azure** | Network Security Group | the only firewall in front of the box; inbound 22 and 80 |
| **AWS** | S3 — `quicklink-originals-<account>` | the upload exactly as it arrived; a lifecycle rule expires objects after 30 days, since they are only needed until the worker has read them |
| **AWS** | S3 — `quicklink-processed-<account>` | the compressed result, handed to browsers as presigned GETs so image bytes never pass back through the app |
| **AWS** | SQS — `quicklink-media-compress` | carries compress jobs from app to worker: 20s long polling, 120s visibility timeout as the handler's deadline |
| **AWS** | SQS — `quicklink-media-compress-dlq` | redrive target after 3 failed receives, 14-day retention — the SQS equivalent of RabbitMQ's dead-letter exchange |
| **AWS** | IAM — one user, one access key | scoped to exactly those two buckets and two queues and nothing else; no `ListBucket` |
| **AWS** | Budgets | emails at forecast >80% and actual >100% of a $1/month budget |

**Azure is only renting a Linux box.** No Azure API, SDK or managed service appears anywhere
in the project — the VM could be Hetzner, EC2, or the Oracle machine this was originally
written for, and nothing but an IP address would change. It is Azure because Azure for
Students supplies the credit. (Oracle's Always Free ARM shape was the original plan and never
had capacity: ~1,300 create attempts, all `Out of host capacity`.)

**AWS is used as services, not as a host.** S3 and SQS are reached through the SDK, and
[`infra/`](infra/) manages them in Terraform so the whole AWS side can be destroyed and
recreated from one command. The VM is not in Terraform — it was made in the portal, and one
machine holding no state worth reproducing is not worth the module.

The split is on purpose, and it is the same judgement in both directions.

**Rented, because the managed version is strictly better.** Object storage and a queue are
pure commodity: durability across availability zones and an API that does not care how much
you put in it. Nothing about running them myself would be interesting, and SeaweedFS on one
VM has exactly one disk. S3 and SQS are also the two cheapest services AWS sells for this
shape of workload — SQS gives a million requests a month in the permanent free tier.

**Self-hosted, because the managed version would hide the project.** Postgres on RDS, Redis
on ElastiCache and three app instances behind an ALB would cost roughly $60/month and delete
the parts worth showing: the load balancer I wrote, the Redis rate limiter, the leader
election. Managed services are the right default at work and the wrong one in a project whose
point is the mechanism.

No code changes between the two worlds — just configuration:

| | Local | Deployed |
|---|---|---|
| Object store | SeaweedFS, `app.s3.endpoint` set, path-style addressing | real S3, endpoint blank, virtual-host addressing |
| Queue | RabbitMQ, `app.queue=rabbit` | SQS, `app.queue=sqs` |
| Credentials | static keys in config | static keys from Terraform, or no keys at all on AWS compute, where the SDK's default chain finds the instance role |

`S3ConfigTest` pins the AWS branch **offline**, by presigning a URL and asserting the host
and signing scope — presigning is local signing, so the real-AWS code path is testable with
no account.

[`infra/`](infra/) holds the Terraform: two buckets with lifecycle rules, the queue and its
redrive policy, an IAM user scoped to exactly those four ARNs, and a monthly budget alarm
with forecast and actual alerts, because the upload endpoint is public and unauthenticated.
[`DEPLOY.md`](DEPLOY.md) is the step-by-step runbook.

---

## Project layout

```
quicklink/
├── docker-compose.yml       all infrastructure + 3 app + 3 worker + the LB
├── pom.xml                  parent POM (Java 21, Boot 4.1.0)
│
├── app-service/             REST API, stateless, scaled ×3
│   └── src/main/
│       ├── java/com/quicklink/app/
│       │   ├── link/        shortener, Base62, cache, click counters, flusher
│       │   ├── media/       upload, S3 client, presigning, status
│       │   └── ratelimit/   Bucket4j filter and its Redis wiring
│       └── resources/
│           ├── db/migration/   Flyway V1–V4
│           └── static/         the live architecture visualisation
│
├── worker/                  RabbitMQ consumer, image compression, scaled ×3
│                            JdbcTemplate rather than JPA — a few narrow UPDATEs
│
├── edge-lb/                 L7 load balancer, plain Java, zero dependencies
│
└── infra/                   Terraform: S3 buckets, SQS + DLQ, scoped IAM, budget alarm
```

## Tests

```bash
./mvnw test        # 74 tests; needs Docker, no hand-started dependencies
```

Pure logic (Base62, backend selection, image compression) runs as plain JUnit with no Spring
context. Everything else uses Testcontainers.

Two things the integration tests do deliberately:

**The worker test publishes through the broker**, in the producer's exact wire format
including the foreign `__TypeId__` header. A direct call to the listener would not notice if
the cross-module type configuration were removed.

**It applies app-service's real migration files** rather than a copied schema, so a worker
test cannot pass against a table shape that production does not have.

Earned the hard way: a test that containerises Postgres but still uses the developer's Redis
is worse than no isolation. It writes state other tests read, and the failures look random
and depend on ordering.

---

Full build log, including the things that went wrong and why, is in
[`springboot-quicklink-development-plan.md`](springboot-quicklink-development-plan.md).
