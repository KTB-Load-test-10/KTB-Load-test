package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStoreResult;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitStore rateLimitStore;
    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        String actualClientId = String.valueOf(clientId);
        Duration effectiveWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());
        long nowEpochSeconds = Instant.now().getEpochSecond();

        try {
            RateLimitStoreResult state = rateLimitStore.increment(actualClientId, windowSeconds);
            long ttlSeconds = state.ttlSeconds();
            long resetEpochSeconds = nowEpochSeconds + ttlSeconds;
            if (state.count() > maxRequests) {
                return RateLimitCheckResult.rejected(
                        maxRequests, windowSeconds, resetEpochSeconds, ttlSeconds);
            }

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    Math.max(0, maxRequests - (int) state.count()),
                    windowSeconds,
                    resetEpochSeconds,
                    ttlSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            // 중요: Redis 장애 중 제한을 우회해 무제한 메시지가 전송되지 않도록 차단한다.
            return RateLimitCheckResult.rejected(
                    maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }
    
}
