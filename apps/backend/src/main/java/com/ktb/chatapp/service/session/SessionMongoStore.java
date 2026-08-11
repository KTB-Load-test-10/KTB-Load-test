package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
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
