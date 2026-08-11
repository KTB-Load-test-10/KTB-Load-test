package com.ktb.chatapp.service.ratelimit;

/**
 * Rate-limit store. Implementations increment a client's counter and establish its TTL atomically.
 */
public interface RateLimitStore {
    RateLimitStoreResult increment(String clientId, long windowSeconds);
}
