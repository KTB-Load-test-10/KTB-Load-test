package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.Room;

/** 생성 HTTP 응답과 roomCreated 이벤트가 같은 요약 DTO를 공유한다. */
public record RoomCreationResult(Room room, RoomResponse response) {
}
