package com.ktb.chatapp.config;

import com.corundumstudio.socketio.store.MemoryStoreFactory;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocketIOStoreFactoryTest {

    @Test
    void usesMemoryStoreOnlyWhenDedicatedSocketRedisHostIsMissing() {
        SocketIOConfig config = socketRedisConfig("", 6379, "");

        assertInstanceOf(MemoryStoreFactory.class, config.socketIOStoreFactory());
    }

    @Test
    void failsImmediatelyWhenConfiguredSocketRedisCannotBeReached() throws IOException {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        SocketIOConfig config = socketRedisConfig("127.0.0.1", unavailablePort, "");

        assertThrows(IllegalStateException.class, config::socketIOStoreFactory);
    }

    private static SocketIOConfig socketRedisConfig(String host, int port, String password) {
        SocketIOConfig config = new SocketIOConfig();
        ReflectionTestUtils.setField(config, "socketRedisHost", host);
        ReflectionTestUtils.setField(config, "socketRedisPort", port);
        ReflectionTestUtils.setField(config, "socketRedisPassword", password);
        return config;
    }
}
