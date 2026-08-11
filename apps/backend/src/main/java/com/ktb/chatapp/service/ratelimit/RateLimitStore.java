package com.ktb.chatapp.service.ratelimit;

/**
 * Data store interface for rate limit storage.
 * Implementations must increment and apply TTL atomically.
 */
public interface RateLimitStore {

    RateLimitStoreResult increment(String clientId, long windowSeconds);
}
