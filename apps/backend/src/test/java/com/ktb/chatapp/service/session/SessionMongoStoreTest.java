package com.ktb.chatapp.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.repository.SessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionMongoStore 로그아웃 세션 삭제")
class SessionMongoStoreTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-1";

    @Mock
    private SessionRepository sessionRepository;

    private SimpleMeterRegistry meterRegistry;
    private SessionMongoStore sessionStore;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sessionStore = new SessionMongoStore(sessionRepository, meterRegistry);
    }

    @Test
    @DisplayName("일치하는 사용자와 세션 ID만 단일 삭제 요청으로 제거한다")
    void delete_DeletesOnlyMatchingUserAndSession() {
        when(sessionRepository.deleteByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(1L);

        sessionStore.delete(USER_ID, SESSION_ID);

        verify(sessionRepository).deleteByUserIdAndSessionId(USER_ID, SESSION_ID);
        verify(sessionRepository, never()).findByUserId(USER_ID);
        assertThat(meterRegistry.get("chat.auth.logout.session_delete.count")
                .tag("outcome", "deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("일치하는 세션이 없으면 다른 세션을 삭제하지 않고 not_found로 기록한다")
    void delete_DoesNotDeleteOtherSessionWhenNoMatchExists() {
        when(sessionRepository.deleteByUserIdAndSessionId(USER_ID, "other-session")).thenReturn(0L);

        sessionStore.delete(USER_ID, "other-session");

        verify(sessionRepository).deleteByUserIdAndSessionId(USER_ID, "other-session");
        assertThat(meterRegistry.get("chat.auth.logout.session_delete.count")
                .tag("outcome", "not_found").counter().count()).isEqualTo(1.0);
    }
}
