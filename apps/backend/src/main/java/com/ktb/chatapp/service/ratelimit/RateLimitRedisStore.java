package com.ktb.chatapp.service.ratelimit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-backed, cluster-wide fixed-window rate limiter. */
@Primary
@Component
@RequiredArgsConstructor
public class RateLimitRedisStore implements RateLimitStore {

    private static final String KEY_PREFIX = "chat:rate-limit:";
    // 중요: 증가, 최초 TTL 설정, TTL 조회를 한 Lua 실행으로 묶어 동시 요청에서도 window를 보존한다.
    private static final DefaultRedisScript<List> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public RateLimitStoreResult increment(String clientId, long windowSeconds) {
        List<?> result = redisTemplate.execute(
                INCREMENT_WITH_TTL, List.of(KEY_PREFIX + clientId), Long.toString(windowSeconds));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Redis rate limit script returned an invalid result");
        }
        long count = ((Number) result.get(0)).longValue();
        long ttlSeconds = Math.max(1L, ((Number) result.get(1)).longValue());
        return new RateLimitStoreResult(count, ttlSeconds);
    }
}
