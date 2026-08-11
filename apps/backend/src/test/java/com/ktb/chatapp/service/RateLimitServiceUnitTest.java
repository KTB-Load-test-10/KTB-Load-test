package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStoreResult;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String CLIENT_ID = "client-1";

    @Mock
    private RateLimitStore rateLimitStore;

    @Test
    @DisplayName("Redis 공통 clientId로 원자 증가 결과를 사용한다")
    void checkRateLimit_UsesSharedClientIdAndReturnsRemainingRequests() {
        when(rateLimitStore.increment(eq(CLIENT_ID), eq(30L)))
                .thenReturn(new RateLimitStoreResult(1, 30));

        RateLimitCheckResult result = new RateLimitService(rateLimitStore)
                .checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
        verify(rateLimitStore).increment(CLIENT_ID, 30L);
    }

    @Test
    @DisplayName("원자 증가 결과가 한도를 넘으면 요청을 차단한다")
    void checkRateLimit_RejectsWhenAtomicCountExceedsLimit() {
        when(rateLimitStore.increment(eq(CLIENT_ID), eq(10L)))
                .thenReturn(new RateLimitStoreResult(4, 8));

        RateLimitCheckResult result = new RateLimitService(rateLimitStore)
                .checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(8);
    }

    @Test
    @DisplayName("null 또는 0초 window는 최소 1초로 정규화한다")
    void checkRateLimit_NormalizesInvalidWindow() {
        when(rateLimitStore.increment(eq(CLIENT_ID), eq(1L)))
                .thenReturn(new RateLimitStoreResult(1, 1));
        RateLimitService service = new RateLimitService(rateLimitStore);

        assertThat(service.checkRateLimit(CLIENT_ID, 3, Duration.ZERO).windowSeconds()).isEqualTo(1);
        assertThat(service.checkRateLimit(CLIENT_ID, 3, null).windowSeconds()).isEqualTo(1);
        verify(rateLimitStore, times(2)).increment(eq(CLIENT_ID), anyLong());
    }

    @Test
    @DisplayName("Redis 장애 시 rate limit 우회를 막기 위해 요청을 차단한다")
    void checkRateLimit_FailsClosedWhenStoreFails() {
        when(rateLimitStore.increment(eq(CLIENT_ID), eq(30L)))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = new RateLimitService(rateLimitStore)
                .checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }
}
