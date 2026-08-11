package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionStore sessionStore;
    public static final long SESSION_TTL_SEC = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();
    private static final long SESSION_TIMEOUT = SESSION_TTL_SEC * 1000;
    public static final long ACTIVITY_REFRESH_INTERVAL_MS = Duration.ofSeconds(30).toMillis();

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SessionData toSessionData(Session session) {
        return SessionData.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .lastActivity(session.getLastActivity())
                .metadata(session.getMetadata())
                .build();
    }

    public SessionCreationResult createSession(String userId, SessionMetadata metadata) {
        try {
            String sessionId = generateSessionId();
            long now = Instant.now().toEpochMilli();
            
            Session session = Session.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .createdAt(now)
                    .lastActivity(now)
                    .metadata(metadata)
                    .expiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC))
                    .build();

            // 중요: Redis는 기존 값을 단일 overwrite로 교체해 이전 토큰을 즉시 무효화한다.
            session = sessionStore.replace(session);
            
            SessionData sessionData = toSessionData(session);

            return SessionCreationResult.builder()
                    .sessionId(sessionId)
                    .expiresIn(SESSION_TTL_SEC)
                    .sessionData(sessionData)
                    .build();

        } catch (Exception e) {
            log.error("Session creation error for userId: {}", userId, e);
            throw new RuntimeException("세션 생성 중 오류가 발생했습니다.", e);
        }
    }

    public SessionValidationResult validateSession(String userId, String sessionId) {
        try {
            if (userId == null || sessionId == null) {
                log.warn("validateSession called with null parameters: userId={}, sessionId={}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_PARAMETERS", "유효하지 않은 세션 파라미터");
            }

            long now = Instant.now().toEpochMilli();
            // 중요: 세션 검증과 sliding TTL 갱신을 하나의 원자적 저장소 연산으로 합친다.
            Session session = sessionStore.validateAndTouch(
                    userId,
                    sessionId,
                    now - SESSION_TIMEOUT,
                    now,
                    Instant.now().plusSeconds(SESSION_TTL_SEC))
                    .orElse(null);
            if (session == null) {
                // 성공 경로는 한 번만 왕복한다. 실패할 때만 원인을 확인해 기존 오류 계약을 유지한다.
                Session existing = sessionStore.findByUserId(userId).orElse(null);
                if (existing != null && sessionId.equals(existing.getSessionId())
                        && now - existing.getLastActivity() > SESSION_TIMEOUT) {
                    removeSession(userId, sessionId);
                    return SessionValidationResult.invalid("SESSION_EXPIRED", "세션이 만료되었습니다.");
                }
                log.warn("Invalid session for userId: {}", userId);
                return SessionValidationResult.invalid("INVALID_SESSION", "세션을 찾을 수 없습니다.");
            }

            SessionData sessionData = toSessionData(session);
            return SessionValidationResult.valid(sessionData);

        } catch (Exception e) {
            log.error("Session validation error for userId: {}, sessionId: {}", userId, sessionId, e);
            return SessionValidationResult.invalid("VALIDATION_ERROR", "세션 검증 중 오류가 발생했습니다.");
        }
    }

    public void updateLastActivity(String userId) {
        try {
            if (userId == null) {
                log.warn("updateLastActivity called with null userId");
                return;
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            if (session == null) {
                log.debug("No session found to update last activity for user: {}", userId);
                return;
            }

            session.setLastActivity(Instant.now().toEpochMilli());
            session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
            sessionStore.save(session);
            
        } catch (Exception e) {
            log.error("Failed to update session activity for user: {}", userId, e);
        }
    }

    public void removeSession(String userId, String sessionId) {
        try {
            if (sessionId != null) {
                sessionStore.delete(userId, sessionId);
            } else {
                sessionStore.deleteAll(userId);
            }
        } catch (Exception e) {
            log.error("Session removal error for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public void removeSession(String userId) {
        removeSession(userId, null);
    }

    public void removeAllUserSessions(String userId) {
        try {
            sessionStore.deleteAll(userId);
        } catch (Exception e) {
            log.error("Remove all sessions error for userId: {}", userId, e);
            throw new RuntimeException("모든 세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public SessionData getActiveSession(String userId) {
        try {
            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                return null;
            }

            return toSessionData(session);
        } catch (Exception e) {
            log.error("Get active session error for userId: {}", userId, e);
            return null;
        }
    }
    
}
