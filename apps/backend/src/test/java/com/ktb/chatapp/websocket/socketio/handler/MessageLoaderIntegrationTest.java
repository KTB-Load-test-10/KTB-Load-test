package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageLoaderIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockitoSpyBean
    private MessageReadStatusService messageReadStatusService;

    private MessageLoader messageLoader;
    private Faker faker;
    private String roomId;
    private String userId;
    private LocalDateTime baseTime;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();
        baseTime = LocalDateTime.now().minusHours(1);

        // MessageLoader 인스턴스 생성
        messageLoader = new MessageLoader(
                messageRepository,
                userRepository,
                fileRepository,
                new MessageResponseMapper(),
                messageReadStatusService
        );

        // 테스트 사용자 생성 및 저장
        User testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();
        userRepository.save(testUser);

        // MessageReadStatusService mock 설정
        doNothing().when(messageReadStatusService).updateReadStatus(anyList(), anyString());
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("100개 메시지 생성 후 초기 30개, 이후 30개씩, 마지막 10개 순차적으로 로드")
    void loadMessages_shouldLoadInPagesOf30ThenFinal10() {
        // Given: 100개의 메시지 생성
        List<Message> messages = IntStream.range(0, 100)
                .mapToObj(this::createAndSaveMessage)
                .toList();

        // When & Then 1: 초기 30개 메시지 로드
        FetchMessagesRequest initialRequest = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse firstResponse = messageLoader.loadMessages(initialRequest, userId);

        assertThat(firstResponse.getMessages()).hasSize(30);
        assertThat(firstResponse.isHasMore()).isTrue();

        // 첫 번째 배치가 가장 오래된 30개 메시지인지 확인
        verifyMessageOrder(firstResponse);

        // When & Then 2: 두 번째 30개 메시지 로드 (before 파라미터 사용)
        long beforeSecond = firstResponse.firstMessageTimestamp();
        FetchMessagesRequest secondRequest = new FetchMessagesRequest(roomId, 30, beforeSecond);
        FetchMessagesResponse secondResponse = messageLoader.loadMessages(secondRequest, userId);

        assertThat(secondResponse.getMessages()).hasSize(30);
        assertThat(secondResponse.isHasMore()).isTrue();

        // 두 번째 배치가 그 다음 30개 메시지인지 확인
        verifyMessageOrder(secondResponse);

        // When & Then 3: 세 번째 30개 메시지 로드
        long beforeThird = secondResponse.firstMessageTimestamp();
        FetchMessagesRequest thirdRequest = new FetchMessagesRequest(roomId, 30, beforeThird);
        FetchMessagesResponse thirdResponse = messageLoader.loadMessages(thirdRequest, userId);

        assertThat(thirdResponse.getMessages()).hasSize(30);
        assertThat(thirdResponse.isHasMore()).isTrue();

        verifyMessageOrder(thirdResponse);

        // When & Then 4: 마지막 10개 메시지 로드
        Long beforeFourth = thirdResponse.firstMessageTimestamp();
        FetchMessagesRequest fourthRequest = new FetchMessagesRequest(roomId, 30, beforeFourth);
        FetchMessagesResponse fourthResponse = messageLoader.loadMessages(fourthRequest, userId);

        assertThat(fourthResponse.getMessages()).hasSize(10);
        assertThat(fourthResponse.isHasMore()).isFalse();

        // 마지막 배치가 가장 최신 메시지인지 확인
        verifyMessageOrder(fourthResponse);

        // 전체 로드된 메시지 수 확인
        int totalLoaded = firstResponse.getMessages().size()
                + secondResponse.getMessages().size()
                + thirdResponse.getMessages().size()
                + fourthResponse.getMessages().size();
        assertThat(totalLoaded).isEqualTo(100);
    }

    @Test
    @DisplayName("메시지가 30개 미만일 때 hasMore가 false")
    void loadMessages_whenLessThan30Messages_hasMoreShouldBeFalse() {
        // Given: 20개의 메시지만 생성
        IntStream.range(0, 20)
                .forEach(this::createAndSaveMessage);

        // When: 초기 30개 요청
        FetchMessagesRequest request = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse response = messageLoader.loadMessages(request, userId);

        // Then: 20개만 반환되고 hasMore는 false
        assertThat(response.getMessages()).hasSize(20);
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("before 파라미터가 모든 메시지보다 오래된 경우 빈 결과 반환")
    void loadMessages_whenBeforeIsOlderThanAllMessages_shouldReturnEmpty() {
        // Given: 메시지 생성 (10시간 전부터 1시간 전까지)
        IntStream.range(0, 10)
                .forEach(this::createAndSaveMessage);

        // When: 모든 메시지보다 오래된 시간으로 요청
        LocalDateTime veryOldTime = LocalDateTime.now().minusHours(100);
        Long beforeEpoch = veryOldTime.toEpochSecond(java.time.ZoneOffset.UTC);
        FetchMessagesRequest request = new FetchMessagesRequest(roomId, 30, beforeEpoch);
        FetchMessagesResponse response = messageLoader.loadMessages(request, userId);

        // Then: 빈 결과 반환
        assertThat(response.getMessages()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("초기 메시지는 limit + 1개의 find만 실행하고 count 쿼리를 실행하지 않는다")
    void loadMessages_shouldUseLimitPlusOneWithoutCountQuery() {
        IntStream.range(0, 31)
                .forEach(this::createAndSaveMessage);

        mongoTemplate.executeCommand(new Document("profile", 0));
        if (mongoTemplate.collectionExists("system.profile")) {
            mongoTemplate.getCollection("system.profile").drop();
        }

        FetchMessagesResponse response;
        mongoTemplate.executeCommand(new Document("profile", 2));
        try {
            response = messageLoader.loadMessages(
                    new FetchMessagesRequest(roomId, 30, null), userId);
        } finally {
            mongoTemplate.executeCommand(new Document("profile", 0));
        }

        List<Document> historyOperations = mongoTemplate.getCollection("system.profile")
                .find()
                .into(new ArrayList<>())
                .stream()
                .filter(operation -> {
                    Document command = operation.get("command", Document.class);
                    return command != null
                            && command.toJson().contains(roomId)
                            && ("messages".equals(command.getString("find"))
                                    || "messages".equals(command.getString("count"))
                                    || "messages".equals(command.getString("aggregate")));
                })
                .toList();

        assertThat(response.getMessages()).hasSize(30);
        assertThat(response.isHasMore()).isTrue();
        assertThat(historyOperations).singleElement().satisfies(operation -> {
            Document command = operation.get("command", Document.class);
            assertThat(command.getString("find")).isEqualTo("messages");
            assertThat(((Number) command.get("limit")).intValue()).isEqualTo(31);
            assertThat(command).doesNotContainKeys("count", "aggregate");
        });
    }

    @Test
    @DisplayName("메시지 초기 조회가 room_timestamp_idx를 사용하고 별도 SORT를 수행하지 않는다")
    void messageHistoryQuery_shouldUseRoomTimestampIndex() {
        IntStream.range(0, 100)
                .forEach(this::createAndSaveMessage);

        List<Document> indexes = mongoTemplate.getCollection("messages")
                .listIndexes()
                .into(new ArrayList<>());

        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("room_timestamp_idx");
            assertThat(index.get("key", Document.class))
                    .isEqualTo(new Document("room", 1).append("timestamp", -1));
        });

        Document findCommand = new Document("find", "messages")
                .append("filter", new Document("room", roomId)
                        .append("timestamp", new Document("$lt", new Date())))
                .append("sort", new Document("timestamp", -1))
                .append("limit", 30);
        Document explain = mongoTemplate.executeCommand(
                new Document("explain", findCommand)
                        .append("verbosity", "executionStats"));

        Document queryPlanner = explain.get("queryPlanner", Document.class);
        Document executionStats = explain.get("executionStats", Document.class);

        assertThat(queryPlanner.toJson()).contains("IXSCAN", "room_timestamp_idx");
        assertThat(queryPlanner.toJson()).doesNotContain("\"stage\": \"SORT\"");
        assertThat(executionStats.getInteger("nReturned")).isEqualTo(30);
        assertThat(((Number) executionStats.get("totalKeysExamined")).longValue())
                .isLessThanOrEqualTo(30L);
        assertThat(((Number) executionStats.get("totalDocsExamined")).longValue())
                .isLessThanOrEqualTo(30L);
    }

    /**
     * 커서 페이징은 timestamp에 strict less-than을 걸기 때문에 같은 시각의 메시지가
     * 배치 경계에 걸리면 건너뛴다. 순번마다 1초씩 벌려 경계를 결정적으로 만든다.
     * timestamp는 @CreatedDate라 최초 저장 때 현재 시각으로 덮어써지므로,
     * 저장 후 한 번 더 갱신한다.
     */
    private Message createAndSaveMessage(int sequence) {
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));

        Message saved = messageRepository.save(message);
        saved.setTimestamp(baseTime.plusSeconds(sequence));
        return messageRepository.save(saved);
    }

    private void verifyMessageOrder(FetchMessagesResponse response) {
        List<Long> timestamps = response.getMessages().stream()
                .map(MessageResponse::getTimestamp)
                .toList();

        // 오름차순 정렬 확인 (오래된 것 → 최신 것)
        for (int i = 0; i < timestamps.size() - 1; i++) {
            assertThat(timestamps.get(i))
                    .withFailMessage("메시지가 오름차순으로 정렬되지 않았습니다: index %d", i)
                    .isLessThanOrEqualTo(timestamps.get(i + 1));
        }
    }
}
