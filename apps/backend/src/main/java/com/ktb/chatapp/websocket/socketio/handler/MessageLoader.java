package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(
                    data.roomId(),
                    data.limit(FetchMessagesRequest.DEFAULT_LIMIT),
                    data.before(LocalDateTime.now()),
                    userId);
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        Pageable pageable = PageRequest.of(0, limit + 1, Sort.by("timestamp").descending());

        List<Message> fetchedMessages = messageRepository
                .findByRoomIdAndTimestampBefore(roomId, before, pageable);

        boolean hasMore = fetchedMessages.size() > limit;
        List<Message> messages = fetchedMessages.subList(0, Math.min(limit, fetchedMessages.size()));

        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messages.reversed();
        
        var messageIds = sortedMessages.stream().map(Message::getId).toList();
        messageReadStatusService.updateReadStatus(roomId, messageIds, userId);

        // 중요: bulk update 직후 응답의 readers도 현재 사용자 상태와 일치시킨다.
        LocalDateTime readAt = LocalDateTime.now();
        sortedMessages.forEach(message -> addReaderIfMissing(message, userId, readAt));

        Map<String, User> usersById = loadUsersById(sortedMessages);
        Map<String, File> filesById = loadFilesById(sortedMessages);
        
        // 메시지 응답 생성
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> messageResponseMapper.mapToMessageResponse(
                        message,
                        getByNullableId(usersById, message.getSenderId()),
                        getByNullableId(filesById, message.getFileId())))
                .collect(Collectors.toList());

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

    private Map<String, User> loadUsersById(List<Message> messages) {
        Set<String> senderIds = messages.stream()
                .map(Message::getSenderId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        if (senderIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<String, File> loadFilesById(List<Message> messages) {
        Set<String> fileIds = messages.stream()
                .map(Message::getFileId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        if (fileIds.isEmpty()) {
            return Map.of();
        }

        return fileRepository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(File::getId, Function.identity()));
    }

    private static <T> T getByNullableId(Map<String, T> valuesById, String id) {
        return id == null ? null : valuesById.get(id);
    }

    private void addReaderIfMissing(Message message, String userId, LocalDateTime readAt) {
        if (message.getReaders() == null) {
            message.setReaders(new ArrayList<>());
        }
        if (message.getReaders().stream().noneMatch(reader -> userId.equals(reader.getUserId()))) {
            message.getReaders().add(Message.MessageReader.builder().userId(userId).readAt(readAt).build());
        }
    }
}
