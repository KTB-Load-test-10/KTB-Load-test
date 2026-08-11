package com.ktb.chatapp.service.session;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=false")
@DisplayName("SessionRedisStore 통합 테스트")
class SessionRedisStoreIntegrationTest {

    private static final String USER_ID = "redis-session-user";
    private static final String SESSION_ID = "redis-session-id";
    private static final String KEY = "chat:session:user:" + USER_ID;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("Redis가 활성 세션 저장소이며 세션 JSON과 TTL을 저장한다")
    void save_StoresSessionWithTtl() {
        Session session = newSession(Instant.now().plusSeconds(120));

        sessionStore.save(session);

        assertThat(sessionStore).isInstanceOf(SessionRedisStore.class);
        assertThat(sessionStore.findByUserId(USER_ID)).contains(session);
        assertThat(redisTemplate.getExpire(KEY)).isPositive();
    }

    @Test
    @DisplayName("세션 검증과 TTL 연장은 한 Redis 원자 연산으로 수행한다")
    void validateAndTouch_UpdatesActivityAndTtl() {
        Session session = newSession(Instant.now().plusSeconds(60));
        sessionStore.save(session);
        long touchedAt = Instant.now().toEpochMilli() + 1;
        Instant newExpiry = Instant.now().plusSeconds(300);

        Session touched = sessionStore.validateAndTouch(
                USER_ID, SESSION_ID, session.getLastActivity(), touchedAt, newExpiry).orElseThrow();

        assertThat(touched.getLastActivity()).isEqualTo(touchedAt);
        assertThat(touched.getExpiresAt()).isEqualTo(newExpiry);
        assertThat(redisTemplate.getExpire(KEY)).isGreaterThan(200L);
    }

    @Test
    @DisplayName("다른 세션 ID는 Redis 키를 삭제하거나 갱신할 수 없다")
    void validateAndTouch_RejectsMismatchedSessionId() {
        Session session = newSession(Instant.now().plusSeconds(120));
        sessionStore.save(session);

        assertThat(sessionStore.validateAndTouch(
                USER_ID, "other-session", session.getLastActivity(), Instant.now().toEpochMilli(), Instant.now().plusSeconds(300)))
                .isEmpty();
        sessionStore.delete(USER_ID, "other-session");

        assertThat(sessionStore.findByUserId(USER_ID)).contains(session);
        assertThat(meterRegistry.get("chat.auth.logout.session_delete.count")
                .tag("outcome", "not_found").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("동시 세션 검증도 모두 유효한 세션만 반환한다")
    void validateAndTouch_IsSafeForConcurrentRequests() throws Exception {
        Session session = newSession(Instant.now().plusSeconds(120));
        sessionStore.save(session);
        Instant newExpiry = Instant.now().plusSeconds(300);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .<Callable<Boolean>>mapToObj(index -> () -> sessionStore.validateAndTouch(
                            USER_ID,
                            SESSION_ID,
                            session.getLastActivity(),
                            Instant.now().toEpochMilli() + index,
                            newExpiry).isPresent())
                    .toList();

            assertThat(executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    return false;
                }
            })).containsOnly(true);
        }
    }

    private static Session newSession(Instant expiresAt) {
        long now = Instant.now().toEpochMilli();
        return Session.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .createdAt(now)
                .lastActivity(now)
                .expiresAt(expiresAt)
                .metadata(new SessionMetadata("test-agent", "127.0.0.1", "test-device"))
                .build();
    }
}
