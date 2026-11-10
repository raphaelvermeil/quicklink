package com.quicklink.app.inspect;

import com.quicklink.app.media.RabbitConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    /** Boot exposes the admin as AmqpAdmin; asking for the concrete RabbitAdmin fails. */
    private final AmqpAdmin amqpAdmin;

    InspectController(JdbcTemplate jdbc, StringRedisTemplate redis, AmqpAdmin amqpAdmin) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.amqpAdmin = amqpAdmin;
    }

    /** The `link` table as it actually is, newest first. */
    @GetMapping("/links")
    List<Map<String, Object>> links(@RequestParam(defaultValue = "8") int limit) {
        return jdbc.queryForList("""
                SELECT l.id, l.short_code, l.target_type, l.target_url, l.media_asset_id,
                       l.created_at, l.expires_at,
                       coalesce(s.clicks, 0) AS persisted_clicks
                  FROM link l
                  LEFT JOIN link_stats s ON s.short_code = l.short_code
                 ORDER BY l.id DESC
                 LIMIT ?
                """, Math.min(limit, MAX_ROWS));
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
        out.put("clicks", scanValues("stats:clicks:*"));
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
        var members = redis.opsForSet().members("stats:dirty");
        if (members != null) {
            members.stream().limit(MAX_KEYS).forEach(m -> rows.add(Map.of("key", m, "value", "pending flush")));
        }
        return rows;
    }

    /** Queue depth and consumer count, straight from the broker. */
    @GetMapping("/queues")
    List<Map<String, Object>> queues() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : new String[] { RabbitConfig.COMPRESS_QUEUE, RabbitConfig.DLQ }) {
            QueueInformation info = amqpAdmin.getQueueInfo(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("messages", info == null ? null : info.getMessageCount());
            row.put("consumers", info == null ? null : info.getConsumerCount());
            out.add(row);
        }
        return out;
    }
}
