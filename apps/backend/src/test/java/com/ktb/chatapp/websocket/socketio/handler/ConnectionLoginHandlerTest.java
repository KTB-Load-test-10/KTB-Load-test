package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                roomLeaveHandler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(client.getSessionId()).thenReturn(socketId);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of(
                "user:" + user.id(), "socket:" + socketId, "room-list"));
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndLeavesRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(roomLeaveHandler).handleLeaveRoom(client, "room-1");
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of(
                "user:" + user.id(), "socket:" + socketId, "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }

    @Test
    void onDisconnect_doesNotClearSharedStateForSupersededSocket() {
        UUID oldSocketId = UUID.randomUUID();
        SocketUser oldUser = new SocketUser(
                "user-1", "tester", "session-1", oldSocketId.toString());
        SocketUser replacement = new SocketUser(
                "user-1", "tester", "session-2", UUID.randomUUID().toString());
        when(client.get("user")).thenReturn(oldUser);
        when(client.getSessionId()).thenReturn(oldSocketId);
        when(connectedUsers.get(oldUser.id())).thenReturn(replacement);

        handler.onDisconnect(client);

        verify(userRooms, never()).get(oldUser.id());
        verify(roomLeaveHandler, never()).handleLeaveRoom(client, "room-1");
        verify(connectedUsers, never()).del(oldUser.id());
        verify(client).disconnect();
    }
}
