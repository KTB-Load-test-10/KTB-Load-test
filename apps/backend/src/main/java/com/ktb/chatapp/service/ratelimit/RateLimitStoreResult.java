package com.ktb.chatapp.service.ratelimit;

public record RateLimitStoreResult(long count, long ttlSeconds) {
}
