package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
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
    private final MongoTemplate mongoTemplate;
    
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
        Session session = sessionRepository.findByUserId(userId).orElse(null);
        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionRepository.delete(session);
        }
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
