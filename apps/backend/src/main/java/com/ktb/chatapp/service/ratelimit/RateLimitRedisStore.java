package com.ktb.chatapp.service.ratelimit;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Primary
@Component
public class RateLimitRedisStore implements RateLimitStore {

    private static final String KEY_PREFIX = "chat:rate-limit:";
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            return {count, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimitRedisStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitStoreResult increment(String clientId, long windowSeconds) {
        List<?> result = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of(KEY_PREFIX + clientId),
                Long.toString(windowSeconds));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("Redis rate limit script returned no result");
        }
        long count = ((Number) result.get(0)).longValue();
        long ttlSeconds = Math.max(1L, ((Number) result.get(1)).longValue());
        return new RateLimitStoreResult(count, ttlSeconds);
    }
}
