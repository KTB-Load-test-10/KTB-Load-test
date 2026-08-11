package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Active session store backed by Redis TTL keys. */
@Primary
@Component
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "chat:session:user:";
    private static final long ACTIVITY_REFRESH_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
    // 중요: 세션 ID·유효 시간을 확인한 뒤 JSON과 Redis TTL을 함께 갱신한다.
    private static final DefaultRedisScript<String> VALIDATE_AND_TOUCH = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return nil end
            local session = cjson.decode(value)
            if session['sessionId'] ~= ARGV[1] or tonumber(session['lastActivity']) < tonumber(ARGV[2]) then
                return nil
            end
            local lastActivity = tonumber(session['lastActivity'])
            local now = tonumber(ARGV[3])
            if now - lastActivity >= tonumber(ARGV[5]) then
                session['lastActivity'] = now
                session['expiresAt'] = ARGV[4]
                local updated = cjson.encode(session)
                redis.call('SET', KEYS[1], updated, 'PX', ARGV[6])
                return updated
            end
            return value
            """, String.class);
    private static final DefaultRedisScript<Long> DELETE_IF_SESSION_MATCHES = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local session = cjson.decode(value)
            if session['sessionId'] == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Optional<Session> findByUserId(String userId) {
        return deserialize(redisTemplate.opsForValue().get(key(userId)));
    }

    @Override
    public Session save(Session session) {
        long ttlMillis = Math.max(1L, Duration.between(Instant.now(), session.getExpiresAt()).toMillis());
        redisTemplate.opsForValue().set(key(session.getUserId()), serialize(session), Duration.ofMillis(ttlMillis));
        return session;
    }

    @Override
    public Optional<Session> validateAndTouch(
            String userId, String sessionId, long activeAfter, long now, Instant expiresAt) {
        long ttlMillis = Math.max(1L, Duration.between(Instant.now(), expiresAt).toMillis());
        String value = redisTemplate.execute(
                VALIDATE_AND_TOUCH,
                List.of(key(userId)),
                sessionId,
                Long.toString(activeAfter),
                Long.toString(now),
                expiresAt.toString(),
                Long.toString(ACTIVITY_REFRESH_INTERVAL_MS),
                Long.toString(ttlMillis));
        return deserialize(value);
    }

    @Override
    public void delete(String userId, String sessionId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            Long deleted = redisTemplate.execute(DELETE_IF_SESSION_MATCHES, List.of(key(userId)), sessionId);
            outcome = deleted != null && deleted > 0 ? "deleted" : "not_found";
        } finally {
            sample.stop(Timer.builder("chat.auth.logout.session_delete.duration")
                    .tag("outcome", outcome).register(meterRegistry));
            Counter.builder("chat.auth.logout.session_delete.count")
                    .tag("outcome", outcome).register(meterRegistry).increment();
        }
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    private Optional<Session> deserialize(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(value, Session.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize Redis session", e);
        }
    }

    private String serialize(Session session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Redis session", e);
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
