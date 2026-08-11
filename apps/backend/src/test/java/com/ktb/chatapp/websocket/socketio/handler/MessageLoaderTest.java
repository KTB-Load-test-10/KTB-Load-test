package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageLoaderTest {
    
    @Mock
    private MessageRepository messageRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private FileRepository fileRepository;
    
    @Mock
    private MessageReadStatusService messageReadStatusService;
    
    @InjectMocks
    private MessageLoader messageLoader;
    
    private Faker faker;
    private List<Message> testMessages;
    private String roomId;
    private String userId;
    
    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();
        
        messageLoader = new MessageLoader(
                messageRepository,
                userRepository,
                fileRepository,
                new MessageResponseMapper(),
                messageReadStatusService
        );
        
        var testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();
        
        // 테스트 메시지 50개 생성 (오름차순: 오래된 것 → 최신 것)
        // i=0: 50시간 전, i=1: 49시간 전, ... i=49: 1시간 전
        testMessages = IntStream.range(0, 50)
                .mapToObj(i -> createMessage(
                        faker.internet().uuid(),
                        LocalDateTime.now().minusHours(50 - i)
                ))
                .toList();
        
        lenient().when(userRepository.findAllById(anySet()))
                .thenReturn(List.of(testUser));
        lenient().doNothing().when(messageReadStatusService).updateReadStatus(anyList(), anyString());
    }
    
    private Message createMessage(String id, LocalDateTime timestamp) {
        Message message = new Message();
        message.setId(id);
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));
        message.setTimestamp(timestamp);
        return message;
    }
    
    @Test
    @DisplayName("loadMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[0~29] (50시간 전 ~ 21시간 전) - 오름차순 상태
        List<Message> first31Messages = testMessages.subList(0, 31);
        
        // DB는 DESC 정렬로 반환한다고 가정 (최신 것 먼저)
        // [21시간 전, 22시간 전, ..., 50시간 전]
        var fetchedMessages = descending(first31Messages);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(fetchedMessages);
        
        // When: 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isTrue();
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [50시간 전, 49시간 전, ..., 21시간 전]
        verifyAscending(result);
    }

    @Test
    @DisplayName("요청 개수보다 하나 더 조회하여 hasMore를 계산하고 초과 메시지는 처리하지 않는다")
    void loadMessages_shouldUseLimitPlusOneWithoutProcessingOverflowMessage() {
        List<Message> fetchedMessages = descending(testMessages.subList(0, 31));
        Message overflowMessage = fetchedMessages.get(30);

        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(fetchedMessages);

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isTrue();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(31);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("timestamp").isDescending())
                .isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> readIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageReadStatusService).updateReadStatus(readIdsCaptor.capture(), eq(userId));
        assertThat(readIdsCaptor.getValue())
                .hasSize(30)
                .doesNotContain(overflowMessage.getId());
    }
    
    private static List<Message> descending(List<Message> messagesInAscendingOrder) {
        return List.copyOf(messagesInAscendingOrder.reversed());
    }
    
    @Test
    @DisplayName("loadInitialMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadInitialMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[20~49] (30시간 전 ~ 1시간 전) - 최신 30개 메시지
        List<Message> last30Messages = testMessages.subList(20, 50);
        
        // DB는 DESC 정렬로 반환 (최신 것부터)
        // [1시간 전, 2시간 전, ..., 30시간 전]
        List<Message> fetchedMessages = descending(last30Messages);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(fetchedMessages);
        
        // When: 초기 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isFalse();
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [30시간 전, 29시간 전, ..., 1시간 전]
        verifyAscending(result);
    }
    
    private static void verifyAscending(FetchMessagesResponse result) {
        for (int i = 0; i < result.getMessages().size() - 1; i++) {
            long current = result.getMessages().get(i).getTimestamp();
            long next = result.getMessages().get(i + 1).getTimestamp();
            assertThat(current).isLessThanOrEqualTo(next);
        }
    }
    
    @Test
    @DisplayName("loadInitialMessages: 에러 시 빈 응답")
    void loadInitialMessages_shouldReturnEmptyOnError() {
        when(messageRepository.findByRoomIdAndTimestampBefore(
                any(), any(LocalDateTime.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB error"));
        
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("sender와 file을 중복 제거하여 각각 한 번에 조회한다")
    void loadMessages_shouldBatchLoadDistinctSendersAndFiles() {
        User firstUser = User.builder()
                .id("user-1")
                .name("첫 번째 사용자")
                .email("user1@example.com")
                .build();
        User secondUser = User.builder()
                .id("user-2")
                .name("두 번째 사용자")
                .email("user2@example.com")
                .build();
        File firstFile = createFile("file-1", "first.png");
        File secondFile = createFile("file-2", "second.pdf");

        Message oldest = createMessage("message-1", LocalDateTime.now().minusMinutes(3));
        oldest.setSenderId(firstUser.getId());
        oldest.setFileId(firstFile.getId());
        Message middle = createMessage("message-2", LocalDateTime.now().minusMinutes(2));
        middle.setSenderId(firstUser.getId());
        middle.setFileId(firstFile.getId());
        Message newest = createMessage("message-3", LocalDateTime.now().minusMinutes(1));
        newest.setSenderId(secondUser.getId());
        newest.setFileId(secondFile.getId());

        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(newest, middle, oldest));
        when(userRepository.findAllById(Set.of("user-1", "user-2")))
                .thenReturn(List.of(firstUser, secondUser));
        when(fileRepository.findAllById(Set.of("file-1", "file-2")))
                .thenReturn(List.of(firstFile, secondFile));

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).extracting(message -> message.getSender().getId())
                .containsExactly("user-1", "user-1", "user-2");
        assertThat(result.getMessages()).extracting(message -> message.getFile().getId())
                .containsExactly("file-1", "file-1", "file-2");
        verify(userRepository, times(1)).findAllById(Set.of("user-1", "user-2"));
        verify(fileRepository, times(1)).findAllById(Set.of("file-1", "file-2"));
        verify(userRepository, never()).findById(anyString());
        verify(fileRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("삭제된 sender와 file 참조는 응답에서 생략한다")
    void loadMessages_shouldIgnoreMissingSenderAndFileReferences() {
        Message message = createMessage("message-1", LocalDateTime.now().minusMinutes(1));
        message.setSenderId("deleted-user");
        message.setFileId("deleted-file");

        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(message));
        when(userRepository.findAllById(Set.of("deleted-user"))).thenReturn(List.of());
        when(fileRepository.findAllById(Set.of("deleted-file"))).thenReturn(List.of());

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).singleElement().satisfies(response -> {
            assertThat(response.getSender()).isNull();
            assertThat(response.getFile()).isNull();
        });
        verify(userRepository).findAllById(Set.of("deleted-user"));
        verify(fileRepository).findAllById(Set.of("deleted-file"));
    }

    @Test
    @DisplayName("메시지가 없으면 sender와 file 저장소를 조회하지 않는다")
    void loadMessages_shouldSkipBatchQueriesForEmptyMessages() {
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).isEmpty();
        verify(userRepository, never()).findAllById(any());
        verify(fileRepository, never()).findAllById(any());
    }

    private static File createFile(String id, String filename) {
        return File.builder()
                .id(id)
                .filename(filename)
                .originalname(filename)
                .mimetype("application/octet-stream")
                .size(1024)
                .build();
    }
}
