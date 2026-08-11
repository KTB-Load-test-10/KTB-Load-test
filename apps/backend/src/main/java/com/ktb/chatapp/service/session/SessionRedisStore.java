package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Primary
@Component
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "chat:session:user:";
    private static final long ACTIVITY_REFRESH_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
    private static final DefaultRedisScript<Long> DELETE_IF_SESSION_MATCHES =
            new DefaultRedisScript<>("""
                    local value = redis.call('GET', KEYS[1])
                    if not value then
                        return 0
                    end
                    local session = cjson.decode(value)
                    if session['sessionId'] == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);
    private static final DefaultRedisScript<String> VALIDATE_AND_TOUCH =
            new DefaultRedisScript<>("""
                    local value = redis.call('GET', KEYS[1])
                    if not value then
                        return nil
                    end

                    local session = cjson.decode(value)
                    if session['sessionId'] ~= ARGV[1] then
                        return nil
                    end

                    local lastActivity = tonumber(session['lastActivity']) or 0
                    local activeAfter = tonumber(ARGV[2])
                    local now = tonumber(ARGV[3])
                    if lastActivity < activeAfter then
                        return nil
                    end

                    if now - lastActivity >= tonumber(ARGV[5]) then
                        session['lastActivity'] = now
                        session['expiresAt'] = ARGV[4]
                        local updated = cjson.encode(session)
                        redis.call('SET', KEYS[1], updated, 'PX', ARGV[6])
                        return updated
                    end

                    return value
                    """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionRedisStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        String value = redisTemplate.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, Session.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize Redis session", e);
        }
    }

    @Override
    public Session save(Session session) {
        try {
            String value = objectMapper.writeValueAsString(session);
            long ttlMillis = Math.max(
                    1L,
                    Duration.between(Instant.now(), session.getExpiresAt()).toMillis());
            redisTemplate.opsForValue().set(
                    key(session.getUserId()), value, Duration.ofMillis(ttlMillis));
            return session;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Redis session", e);
        }
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
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, Session.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize Redis session", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        redisTemplate.execute(
                DELETE_IF_SESSION_MATCHES,
                List.of(key(userId)),
                sessionId);
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
