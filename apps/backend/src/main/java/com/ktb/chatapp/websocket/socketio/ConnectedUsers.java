package com.ktb.chatapp.websocket.socketio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectedUsers {
    
    private static final String USER_SOCKET_KEY_PREFIX = "conn_users:userid:";
    
    private final ChatDataStore chatDataStore;

    public ConnectedUsers(@Qualifier("connectedUsersStore") ChatDataStore chatDataStore) {
        this.chatDataStore = chatDataStore;
    }
    
    public SocketUser get(String userId) {
        return chatDataStore.get(buildKey(userId), SocketUser.class).orElse(null);
    }
    
    public void set(String userId, SocketUser sockerUser) {
        chatDataStore.set(buildKey(userId), sockerUser);
    }
    
    public void del(String userId) {
        chatDataStore.delete(buildKey(userId));
    }
    
    public int size() {
        return chatDataStore.size();
    }
    
    private String buildKey(String userId) {
        return USER_SOCKET_KEY_PREFIX + userId;
    }
}
