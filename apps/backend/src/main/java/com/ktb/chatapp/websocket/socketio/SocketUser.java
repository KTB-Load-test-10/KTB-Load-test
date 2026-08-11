package com.ktb.chatapp.websocket.socketio;

import java.io.Serializable;

import com.ktb.chatapp.dto.UserResponse;

/**
 * Socket User Record
 * @param id user id
 * @param name user name
 * @param authSessionId user auth session id
 * @param socketId user websocket session id
 */
public record SocketUser(
        String id,
        String name,
        String authSessionId,
        String socketId,
        UserResponse userResponse) implements Serializable {

    public SocketUser(String id, String name, String authSessionId, String socketId) {
        this(
                id,
                name,
                authSessionId,
                socketId,
                UserResponse.builder().id(id).name(name).profileImage("").build());
    }
}
