package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.RoomCursor;
import com.ktb.chatapp.model.Room;
import java.util.List;

public interface RoomRepositoryCustom {
    List<Room> findCursorPage(RoomCursor cursor, int limit);
}
