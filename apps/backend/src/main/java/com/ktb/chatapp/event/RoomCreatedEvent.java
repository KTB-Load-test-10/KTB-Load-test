package com.ktb.chatapp.event;

import com.ktb.chatapp.dto.RoomListResponse;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RoomCreatedEvent extends ApplicationEvent {
    private final RoomListResponse roomResponse;

    public RoomCreatedEvent(Object source, RoomListResponse roomResponse) {
        super(source);
        this.roomResponse = roomResponse;
    }
}
