package com.ktb.chatapp.event;

import com.ktb.chatapp.dto.RoomListResponse;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RoomUpdatedEvent extends ApplicationEvent {
    private final String roomId;
    private final RoomListResponse roomResponse;

    public RoomUpdatedEvent(Object source, String roomId, RoomListResponse roomResponse) {
        super(source);
        this.roomId = roomId;
        this.roomResponse = roomResponse;
    }
}
