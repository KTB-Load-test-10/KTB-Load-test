package com.ktb.chatapp.service.ratelimit;

/** Result of one atomic rate-limit counter increment. */
public record RateLimitStoreResult(long count, long ttlSeconds) {
}
