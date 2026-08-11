package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final Set<String> pendingRooms = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "room-activity-coalescer");
        thread.setDaemon(true);
        return thread;
    });

    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        // 중요: 같은 방의 연속 메시지는 1초 동안 하나의 count와 이벤트로 병합한다.
        if (pendingRooms.add(roomId)) {
            scheduler.schedule(() -> publishLatestActivity(roomId), 1, TimeUnit.SECONDS);
        }
    }

    void publishLatestActivity(String roomId) {
        pendingRooms.remove(roomId);
        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
