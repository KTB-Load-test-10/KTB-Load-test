package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.repository.RateLimitRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 * Uses RateLimitRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {
    
    private final RateLimitRepository rateLimitRepository;
    
    @Override
    public RateLimitStoreResult increment(String clientId, long windowSeconds) {
        Instant now = Instant.now();
        RateLimit rateLimit = rateLimitRepository.findByClientId(clientId).orElse(null);
        if (rateLimit == null || !rateLimit.getExpiresAt().isAfter(now)) {
            rateLimit = RateLimit.builder()
                    .clientId(clientId)
                    .count(1)
                    .expiresAt(now.plusSeconds(windowSeconds))
                    .build();
        } else {
            rateLimit.setCount(rateLimit.getCount() + 1);
        }
        RateLimit saved = rateLimitRepository.save(rateLimit);
        long ttlSeconds = Math.max(1L, saved.getExpiresAt().getEpochSecond() - now.getEpochSecond());
        return new RateLimitStoreResult(saved.getCount(), ttlSeconds);
    }
}
