package com.quicklink.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RedisConnectionTest {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void writesAndReadsBackAValue() {
        redis.opsForValue().set("quicklink:smoke", "ok", Duration.ofSeconds(30));

        assertEquals("ok", redis.opsForValue().get("quicklink:smoke"));
    }
}
