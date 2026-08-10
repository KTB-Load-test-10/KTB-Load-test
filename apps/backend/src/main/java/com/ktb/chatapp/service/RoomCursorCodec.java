package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.RoomCursor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Base64URL 인코딩된 불투명 cursor. 외부 JSON ObjectMapper Bean에 의존하지 않는다. */
@Component
public class RoomCursorCodec {

    private static final String SEPARATOR = "\n";

    public String encode(RoomCursor cursor) {
        String value = cursor.createdAt() + SEPARATOR + cursor.id() + SEPARATOR + cursor.nextPage();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public RoomCursor decode(String encodedCursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            String[] values = decoded.split(SEPARATOR, -1);
            if (values.length != 3 || values[1].isBlank()) {
                throw new IllegalArgumentException();
            }
            RoomCursor cursor = new RoomCursor(
                    LocalDateTime.parse(values[0]), values[1], Integer.parseInt(values[2]));
            if (cursor.nextPage() < 1) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("잘못된 채팅방 cursor입니다.", e);
        }
    }
}
