package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.util.Optional;
import java.time.Instant;

/**
 * Data store interface for session storage.
 * Provides operations for storing and retrieving session data.
 */
public interface SessionStore {
    
    /**
     * Find session by user ID
     *
     * @param userId the user identifier
     * @return Optional containing the Session if found, empty otherwise
     */
    Optional<Session> findByUserId(String userId);
    
    /**
     * Save or update session
     *
     * @param session the session to save
     * @return the saved session
     */
    Session save(Session session);

    /** 세션 일치·만료 검증과 활동 시각 갱신을 한 저장소 왕복으로 수행한다. */
    Optional<Session> validateAndTouch(
            String userId, String sessionId, long activeAfter, long now, Instant expiresAt);
    
    /**
     * Delete all sessions for a user
     *
     * @param userId the user identifier
     */
    void deleteAll(String userId);
    
    void delete(String userId, String sessionId);
}
