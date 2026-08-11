package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStoreResult;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String CLIENT_ID = "client-1";

    @Mock
    private RateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
    }

    @Test
    @DisplayName("최초 요청은 원자적 증가 결과로 허용되고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_ReturnsRemainingCount() {
        when(rateLimitStore.increment(CLIENT_ID, 30))
                .thenReturn(new RateLimitStoreResult(1, 30));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
        verify(rateLimitStore).increment(CLIENT_ID, 30);
    }

    @Test
    @DisplayName("한도 이내 연속 요청은 Redis 카운트를 그대로 반영한다")
    void checkRateLimit_BelowLimit_ReturnsUpdatedRemainingCount() {
        when(rateLimitStore.increment(CLIENT_ID, 30))
                .thenReturn(new RateLimitStoreResult(2, 20));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("원자적 카운트가 한도를 초과하면 차단한다")
    void checkRateLimit_Exceeded_ReturnsRetryAfter() {
        when(rateLimitStore.increment(CLIENT_ID, 30))
                .thenReturn(new RateLimitStoreResult(4, 10));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("0초 window는 최소 1초로 정규화한다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(rateLimitStore.increment(CLIENT_ID, 1))
                .thenReturn(new RateLimitStoreResult(1, 1));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("null window는 최소 1초로 정규화한다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(rateLimitStore.increment(CLIENT_ID, 1))
                .thenReturn(new RateLimitStoreResult(1, 1));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장소 실패 시 요청을 허용하는 기존 정책을 유지한다")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.increment(CLIENT_ID, 30))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 결정적인 Redis key로 처리한다")
    void checkRateLimit_NullClientId_UsesNullStringKey() {
        when(rateLimitStore.increment("null", 30))
                .thenReturn(new RateLimitStoreResult(1, 30));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).increment("null", 30);
    }
}
