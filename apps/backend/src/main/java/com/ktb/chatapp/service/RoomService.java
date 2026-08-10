package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final RoomCursorCodec roomCursorCodec;
    private final MeterRegistry meterRegistry;

    public RoomsResponse getAllRooms(String name) {
        return getAllRooms(name, DEFAULT_PAGE_SIZE, null);
    }

    /**
     * 페이지당 MongoDB 작업 수: 방 조회 1회, count 1회, 사용자 batch 1회, 메시지 집계 1회.
     * 기존 방식은 전체 방 조회 후 각 방의 creator, participant, 최근 메시지를 다시 조회해 N+1이 발생했다.
     */
    public RoomsResponse getAllRooms(String name, int requestedLimit, String encodedCursor) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            int limit = normalizeLimit(requestedLimit);
            RoomCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                    ? null : roomCursorCodec.decode(encodedCursor);

            // 권장 변경: DB 범위 조건과 정렬을 사용하고 limit + 1로 다음 페이지 존재만 판별한다.
            List<Room> fetchedRooms = roomRepository.findCursorPage(cursor, limit + 1);
            boolean hasMore = fetchedRooms.size() > limit;
            List<Room> pageRooms = hasMore ? fetchedRooms.subList(0, limit) : fetchedRooms;

            Map<String, User> usersById = findUsersByRoomIds(pageRooms);
            Map<String, Integer> recentMessageCounts = messageRepository.countRecentMessagesByRoomIds(
                    pageRooms.stream().map(Room::getId).collect(Collectors.toSet()),
                    LocalDateTime.now().minusMinutes(30));

            List<RoomResponse> responses = pageRooms.stream()
                    .map(room -> mapToRoomResponse(room, name, usersById,
                            recentMessageCounts.getOrDefault(room.getId(), 0)))
                    .collect(Collectors.toList());

            long total = roomRepository.count();
            int page = cursor == null ? 0 : cursor.nextPage();
            String nextCursor = hasMore && !pageRooms.isEmpty()
                    ? createNextCursor(pageRooms.get(pageRooms.size() - 1), page + 1)
                    : null;

            PageMetadata metadata = PageMetadata.builder()
                    .total(total)
                    .page(page)
                    .pageSize(limit)
                    .totalPages(total == 0 ? 0 : (long) Math.ceil((double) total / limit))
                    .hasMore(hasMore)
                    .nextCursor(nextCursor)
                    .currentCount(responses.size())
                    .build();

            return RoomsResponse.builder().success(true).data(responses).metadata(metadata).build();
        } catch (RuntimeException e) {
            outcome = "error";
            log.error("방 목록 조회 에러", e);
            throw e;
        } finally {
            sample.stop(Timer.builder("chat.rooms.list.duration")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();
            boolean isMongoConnected;
            long latency;
            try {
                roomRepository.findOneForHealthCheck();
                latency = System.currentTimeMillis() - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                latency = 0;
                isMongoConnected = false;
            }
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom().map(Room::getCreatedAt).orElse(null);
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder().connected(isMongoConnected).latency(latency).build());
            return HealthResponse.builder().success(true).services(services).lastActivity(lastActivity).build();
        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder().success(false).services(new HashMap<>()).build();
        }
    }

    public RoomCreationResult createRoom(CreateRoomRequest request, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));
        Room room = new Room();
        room.setName(request.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        // 문제: 저장 후 mapper가 creator/participant/count를 다시 조회했다.
        // 권장 변경: 이미 보유한 creator와 초기 메시지 수 0으로 summary를 한 번만 조립한다.
        RoomResponse response = mapToRoomResponse(savedRoom, name, Map.of(creator.getId(), creator), 0L)
                .toBuilder().joined(true).build();
        try {
            eventPublisher.publishEvent(new RoomCreatedEvent(this, response));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }
        return new RoomCreationResult(savedRoom, response);
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) return null;
        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));
        if (room.isHasPassword() && (password == null || !passwordEncoder.matches(password, room.getPassword()))) {
            throw new RuntimeException("비밀번호가 일치하지 않습니다.");
        }
        if (!room.getParticipantIds().contains(user.getId())) {
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        try {
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, mapToRoomResponse(room, name)));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }
        return room;
    }

    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;
        return mapToRoomResponse(room, name, findUsersByRoomIds(List.of(room)),
                recentMessageCounter.countRecentMessages(room.getId()));
    }

    private RoomResponse mapToRoomResponse(Room room, String name, Map<String, User> usersById, long recentMessageCount) {
        User creator = usersById.get(room.getCreator());
        List<UserResponse> participants = room.getParticipantIds() == null ? List.of() : room.getParticipantIds().stream()
                .map(usersById::get).filter(Objects::nonNull).map(UserResponse::from).toList();
        return RoomResponse.builder()
                .id(room.getId()).name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword()).creator(creator == null ? null : UserResponse.from(creator))
                .participants(participants).createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
                .isCreator(creator != null && Objects.equals(creator.getEmail(), name))
                .recentMessageCount((int) recentMessageCount).joined(false).build();
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
    }

    private Map<String, User> findUsersByRoomIds(Collection<Room> rooms) {
        Set<String> ids = new HashSet<>();
        for (Room room : rooms) {
            if (room.getCreator() != null) ids.add(room.getCreator());
            if (room.getParticipantIds() != null) ids.addAll(room.getParticipantIds());
        }
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<String, User> users = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> users.put(user.getId(), user));
        return users;
    }

    private String createNextCursor(Room lastRoom, int nextPage) {
        if (lastRoom.getCreatedAt() == null) throw new IllegalStateException("채팅방 생성 시각이 없습니다.");
        return roomCursorCodec.encode(new RoomCursor(lastRoom.getCreatedAt(), lastRoom.getId(), nextPage));
    }
}
