package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;

    // 중요: validateAndTouch가 세션 검증과 활동 시각 갱신을 한 번의 MongoDB 연산으로 수행한다.
    private final MongoTemplate mongoTemplate;

    private final MeterRegistry meterRegistry;

    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return sessionRepository.findByUserId(userId);
    }
    
    @Override
    public Session save(Session session) {
        return sessionRepository.save(session);
    }

    @Override
    public Optional<Session> validateAndTouch(
            String userId, String sessionId, long activeAfter, long now, Instant expiresAt) {
        Query query = Query.query(Criteria.where("userId").is(userId)
                .and("sessionId").is(sessionId)
                .and("lastActivity").gte(activeAfter));
        Update update = new Update().set("lastActivity", now).set("expiresAt", expiresAt);
        Session session = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), Session.class);
        return Optional.ofNullable(session);
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            // 중요: 조회 후 삭제 대신 두 식별자를 조건으로 한 단일 MongoDB 삭제를 수행한다.
            long deletedCount = sessionRepository.deleteByUserIdAndSessionId(userId, sessionId);
            outcome = deletedCount > 0 ? "deleted" : "not_found";
        } finally {
            sample.stop(Timer.builder("chat.auth.logout.session_delete.duration")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            Counter.builder("chat.auth.logout.session_delete.count")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
