package com.ktb.chatapp.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface MessageRepositoryCustom {
    Map<String, Integer> countRecentMessagesByRoomIds(Set<String> roomIds, LocalDateTime since);

    /**
     * 한 방에 속하면서 아직 읽지 않은 메시지만 한 번의 updateMany로 갱신한다.
     */
    long markMessagesAsRead(String roomId, List<String> messageIds, String userId, LocalDateTime readAt);
}
