package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ktb.chatapp.model.Room;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 채팅방 목록과 목록 Socket 이벤트 전용의 경량 응답이다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomListResponse {

    @JsonProperty("_id")
    private String id;
    private String name;
    private boolean hasPassword;
    private int participantsCount;
    private Integer recentMessageCount;

    @JsonIgnore
    private LocalDateTime createdAtDateTime;

    /** 목록에는 참여자 정보 전체가 아니라 숫자만 노출한다. */
    public static RoomListResponse from(Room room, int recentMessageCount) {
        int participantsCount = room.getParticipantIds() == null ? 0 : room.getParticipantIds().size();
        return RoomListResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .participantsCount(participantsCount)
                .recentMessageCount(recentMessageCount)
                .createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
                .build();
    }

    @JsonGetter("createdAt")
    public String getCreatedAt() {
        return createdAtDateTime
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toString();
    }
}
