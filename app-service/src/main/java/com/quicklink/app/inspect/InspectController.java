package com.quicklink.app.inspect;

import com.quicklink.app.link.ClickCounters;
import com.quicklink.app.link.ClickStatsFlusher;
import com.quicklink.app.media.RabbitConfig;
import com.quicklink.app.media.SqsProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Read-only windows onto the system's actual state, so the front page can show real
 * database rows and real Redis keys rather than an illustration.
 *
 * DEMO ONLY. This deliberately exposes internals — raw rows, cache keys, queue depth.
 * A production deployment would put it behind auth or drop it entirely.
 */
@RestController
@RequestMapping("/api/inspect")
class InspectController {

    /** Hard cap on everything returned here: this endpoint must never become expensive. */
    private static final int MAX_ROWS = 12;
    private static final int MAX_KEYS = 40;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ClickStatsFlusher flusher;
    /**
     * Boot exposes the admin as AmqpAdmin; asking for the concrete RabbitAdmin fails.
     * Optional because the Rabbit beans are conditional on app.queue=rabbit.
     */
    private final ObjectProvider<AmqpAdmin> amqpAdmin;
    /** The mirror image: present only when app.queue=sqs. */
    private final ObjectProvider<SqsClient> sqsClient;
    private final ObjectProvider<SqsProperties> sqsProperties;
    private final String queueMode;
    private final String s3Endpoint;

    InspectController(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectProvider<AmqpAdmin> amqpAdmin,
                      ObjectProvider<SqsClient> sqsClient, ObjectProvider<SqsProperties> sqsProperties,
                      @Value("${app.queue:rabbit}") String queueMode,
                      @Value("${app.s3.endpoint:}") String s3Endpoint,
                      ClickStatsFlusher flusher) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.flusher = flusher;
        this.amqpAdmin = amqpAdmin;
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.queueMode = queueMode;
        this.s3Endpoint = s3Endpoint;
    }

    /**
     * Which infrastructure this instance is actually wired to, so the diagram on the
     * front page can name it. Without this the boxes would be hardcoded to the local
     * stack and would keep saying "RabbitMQ" while a job was really going through SQS —
     * a visualisation that lies is worse than none.
     */
    @GetMapping("/mode")
    Map<String, String> mode() {
        return Map.of(
                "queue", queueMode,
                // A blank endpoint is what selects real AWS; anything else is the local
                // S3-compatible store, which in this project is SeaweedFS.
                "store", s3Endpoint.isBlank() ? "s3" : "seaweedfs");
    }

    /**
     * The `link` table as it actually is, newest first — plus the click delta still
     * sitting in Redis.
     *
     * Both halves are needed to make write-behind legible. `persisted_clicks` only
     * moves when the flusher runs (once a minute), so on its own it looks as though a
     * click was never counted. `pending_clicks` is where the click actually went.
     */
    @GetMapping("/links")
    List<Map<String, Object>> links(@RequestParam(defaultValue = "8") int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.id, l.short_code, l.target_type, l.target_url, l.media_asset_id,
                       l.created_at, l.expires_at,
                       coalesce(s.clicks, 0) AS persisted_clicks
                  FROM link l
                  LEFT JOIN link_stats s ON s.short_code = l.short_code
                 ORDER BY l.id DESC
                 LIMIT ?
                """, Math.min(limit, MAX_ROWS));

        if (rows.isEmpty()) {
            return rows;
        }
        // One round trip for all the counters rather than one GET per row.
        List<String> keys = rows.stream()
                .map(r -> ClickCounters.counterKey((String) r.get("short_code")))
                .toList();
        List<String> pending = redis.opsForValue().multiGet(keys);

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>(rows.get(i));
            String raw = (pending == null) ? null : pending.get(i);
            row.put("pending_clicks", raw == null ? 0L : Long.parseLong(raw));
            out.add(row);
        }
        return out;
    }

    /**
     * Runs the flush immediately instead of waiting for the next scheduled tick, so the
     * hand-off from Redis to Postgres can be watched rather than waited out.
     * A demo affordance only — in production the trigger is @Scheduled.
     */
    @PostMapping("/flush")
    Map<String, Object> flushNow() {
        flusher.flush();
        return Map.of("flushed", true);
    }

    /** The `media_asset` table, for watching the status machine move. */
    @GetMapping("/media")
    List<Map<String, Object>> media(@RequestParam(defaultValue = "6") int limit) {
        return jdbc.queryForList("""
                SELECT id, status, content_type, original_bytes, processed_bytes,
                       error_message, updated_at
                  FROM media_asset
                 ORDER BY id DESC
                 LIMIT ?
                """, Math.min(limit, MAX_ROWS));
    }

    /**
     * Live Redis contents, grouped by what each key family is for. Uses SCAN rather
     * than KEYS: KEYS walks the whole keyspace and blocks the server.
     */
    @GetMapping("/redis")
    Map<String, List<Map<String, Object>>> redisState() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("cache", scanValues("link:code:*"));
        out.put("buckets", scanValues("rate:ip:*"));
        out.put("clicks", scanValues(ClickCounters.COUNTER_PREFIX + "*"));
        out.put("dirty", dirtySet());
        return out;
    }

    private List<Map<String, Object>> scanValues(String pattern) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(pattern).count(64).build())) {
            while (cursor.hasNext() && rows.size() < MAX_KEYS) {
                String key = cursor.next();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", key);
                // A rate-limit bucket is an opaque serialized blob, not text, so report
                // its size (STRLEN) rather than trying to render the bytes.
                row.put("value", pattern.startsWith("rate:")
                        ? "<" + redis.opsForValue().size(key) + " byte bucket>"
                        : redis.opsForValue().get(key));
                row.put("ttl", redis.getExpire(key));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> dirtySet() {
        List<Map<String, Object>> rows = new ArrayList<>();
        var members = redis.opsForSet().members(ClickCounters.DIRTY_SET);
        if (members != null) {
            members.stream().limit(MAX_KEYS).forEach(m -> rows.add(Map.of("key", m, "value", "pending flush")));
        }
        return rows;
    }

    /**
     * Queue depth and consumer count, straight from the broker.
     *
     * Degrades rather than fails: there may be no Rabbit at all (app.queue=sqs), or it
     * may simply be unreachable. A diagnostics endpoint that 500s when one dependency
     * is down is useless precisely when you need it.
     */
    @GetMapping("/queues")
    List<Map<String, Object>> queues() {
        SqsClient sqs = sqsClient.getIfAvailable();
        return sqs == null ? rabbitQueues() : sqsQueues(sqs);
    }

    /**
     * SQS reports depth as an attribute rather than as queue metadata, and has no
     * consumer count at all: nothing registers as a consumer, it is just whoever calls
     * ReceiveMessage. In-flight is the closest equivalent — messages handed out and not
     * yet deleted.
     */
    private List<Map<String, Object>> sqsQueues(SqsClient sqs) {
        SqsProperties properties = sqsProperties.getObject();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(sqsRow(sqs, properties.queueUrl()));
        // Configured only for display, so a missing DLQ url is not an error.
        if (!properties.dlqUrl().isBlank()) {
            out.add(sqsRow(sqs, properties.dlqUrl()));
        }
        return out;
    }

    private Map<String, Object> sqsRow(SqsClient sqs, String url) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", url.substring(url.lastIndexOf('/') + 1));
        try {
            Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(b -> b.queueUrl(url)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .attributes();
            row.put("messages", Integer.valueOf(
                    attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
            // Deliberately null: SQS has no consumer count. Nothing registers as a
            // consumer — it is whoever happens to call ReceiveMessage — so in-flight
            // (handed out, not yet deleted) is the closest equivalent.
            row.put("consumers", null);
            row.put("detail", attributes.get(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE) + " in flight");
        } catch (Exception unreachable) {
            row.put("messages", null);
            row.put("consumers", null);
            row.put("error", "queue unreachable");
        }
        return row;
    }

    private List<Map<String, Object>> rabbitQueues() {
        List<Map<String, Object>> out = new ArrayList<>();
        AmqpAdmin admin = amqpAdmin.getIfAvailable();
        for (String name : new String[] { RabbitConfig.COMPRESS_QUEUE, RabbitConfig.DLQ }) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            QueueInformation info = null;
            if (admin != null) {
                try {
                    info = admin.getQueueInfo(name);
                } catch (Exception brokerUnreachable) {
                    row.put("error", "broker unreachable");
                }
            } else {
                row.put("error", "no AMQP broker configured");
            }
            row.put("messages", info == null ? null : info.getMessageCount());
            row.put("consumers", info == null ? null : info.getConsumerCount());
            out.add(row);
        }
        return out;
    }
}
