package com.ktb.chatapp.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

// 최초는 limit 개수만 조회, 이후에는 before 메시지 ID 기준으로 이전 메시지 조회
public record FetchMessagesRequest(String roomId, Integer limit, Long before) {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;

    public int limit(int defaultLimit) {
        int normalizedDefault = defaultLimit > 0
                ? Math.min(defaultLimit, MAX_LIMIT)
                : DEFAULT_LIMIT;

        if (limit == null || limit <= 0) {
            return normalizedDefault;
        }
        return Math.min(limit, MAX_LIMIT);
    }
    
    public LocalDateTime before(LocalDateTime defaultBeforeTime) {
        if (before != null && before > 0) {
            return LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(before),
                    ZoneId.systemDefault()
            );
        }
        return defaultBeforeTime;
    }
}
