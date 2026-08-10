package com.ktb.chatapp.dto;

import java.time.LocalDateTime;

/**
 * 목록 cursor의 내부 표현이다. 클라이언트에는 Base64URL 문자열로만 노출한다.
 */
public record RoomCursor(LocalDateTime createdAt, String id, int nextPage) {
}
