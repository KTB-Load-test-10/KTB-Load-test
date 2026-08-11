package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageRepository messageRepository;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public long updateReadStatus(String roomId, List<String> messageIds, String userId) {
        if (roomId == null || messageIds.isEmpty()) {
            return 0;
        }

        try {
            long modified = messageRepository.markMessagesAsRead(
                    roomId, messageIds.stream().distinct().toList(), userId, LocalDateTime.now());
            log.debug("Read status updated for {} of {} messages by user {}",
                    modified, messageIds.size(), userId);
            return modified;
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
            return 0;
        }
    }
}
