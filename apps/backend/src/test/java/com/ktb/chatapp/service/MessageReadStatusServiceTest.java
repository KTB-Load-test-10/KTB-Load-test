package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadStatusServiceTest {

    @Mock private MessageRepository messageRepository;

    @Test
    void updateReadStatus_deduplicatesIdsAndUsesOneBulkOperation() {
        MessageReadStatusService service = new MessageReadStatusService(messageRepository);
        when(messageRepository.markMessagesAsRead(
                eq("room-1"), any(), eq("user-1"), any(LocalDateTime.class)))
                .thenReturn(2L);

        long modified = service.updateReadStatus(
                "room-1", List.of("message-1", "message-1", "message-2"), "user-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).markMessagesAsRead(
                eq("room-1"), ids.capture(), eq("user-1"), any(LocalDateTime.class));
        assertThat(ids.getValue()).containsExactly("message-1", "message-2");
        assertThat(modified).isEqualTo(2L);
    }
}
