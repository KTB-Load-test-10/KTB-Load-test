package com.ktb.chatapp.repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

public interface MessageRepositoryCustom {
    Map<String, Integer> countRecentMessagesByRoomIds(Set<String> roomIds, LocalDateTime since);
}
