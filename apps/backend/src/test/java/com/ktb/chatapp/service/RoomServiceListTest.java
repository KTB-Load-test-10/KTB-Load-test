package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.RoomListResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RoomServiceListTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    void getAllRooms_returnsLightweightItemsWithoutUserBatchLookup() throws Exception {
        Room room = Room.builder()
                .id("room-1")
                .name("목록용 방")
                .hasPassword(false)
                .createdAt(LocalDateTime.of(2026, 8, 11, 10, 0))
                .participantIds(Set.of("user-1", "user-2"))
                .build();
        when(roomRepository.findCursorPage(isNull(), org.mockito.ArgumentMatchers.eq(21))).thenReturn(List.of(room));
        when(roomRepository.count()).thenReturn(1L);
        when(messageRepository.countRecentMessagesByRoomIds(anySet(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("room-1", 3));

        RoomService service = new RoomService(
                roomRepository, userRepository, messageRepository, recentMessageCounter, passwordEncoder,
                eventPublisher, new RoomCursorCodec(), new SimpleMeterRegistry());

        RoomsResponse response = service.getAllRooms("viewer@example.com", 20, null);

        RoomListResponse item = response.getData().getFirst();
        assertThat(item.getId()).isEqualTo("room-1");
        assertThat(item.getParticipantsCount()).isEqualTo(2);
        assertThat(item.getRecentMessageCount()).isEqualTo(3);
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(item))
                .doesNotContain("\"participants\"")
                .contains("\"participantsCount\":2");
        verify(roomRepository).findCursorPage(null, 21);
        verifyNoInteractions(userRepository);
    }
}
