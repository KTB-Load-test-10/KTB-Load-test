package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
class SocketIORedisClusterIntegrationTest {

    private static final String ROOM = "cluster-room";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    private SocketIOServer server1;
    private SocketIOServer server2;
    private TestSocketClient userA;
    private TestSocketClient userB;

    @AfterEach
    void tearDown() {
        if (userA != null) {
            userA.close();
        }
        if (userB != null) {
            userB.close();
        }
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
    }

    @Test
    void broadcastsRoomEventFromBackendOneToClientOnBackendTwo() throws Exception {
        int port1 = availablePort();
        int port2 = availablePort();
        server1 = createServer(port1);
        server2 = createServer(port2);

        ChatDataStore usersOnNode1 = new StoreFactoryChatDataStore(
                server1.getConfiguration().getStoreFactory(),
                "chatapp:socketio:connected-users-test");
        ChatDataStore usersOnNode2 = new StoreFactoryChatDataStore(
                server2.getConfiguration().getStoreFactory(),
                "chatapp:socketio:connected-users-test");
        SocketUser user = new SocketUser("user-a", "A", "auth-session-a", "socket-a");
        usersOnNode1.set(user.id(), user);
        assertEquals(user, usersOnNode2.get(user.id(), SocketUser.class).orElseThrow());

        ChatDataStore roomsOnNode1 = new StoreFactoryChatDataStore(
                server1.getConfiguration().getStoreFactory(),
                "chatapp:socketio:user-rooms-test");
        ChatDataStore roomsOnNode2 = new StoreFactoryChatDataStore(
                server2.getConfiguration().getStoreFactory(),
                "chatapp:socketio:user-rooms-test");
        roomsOnNode1.addToSet(user.id(), ROOM);
        roomsOnNode2.addToSet(user.id(), "second-room");
        assertEquals(
                java.util.Set.of(ROOM, "second-room"),
                roomsOnNode1.get(user.id(), java.util.Set.class).orElseThrow());

        registerRoomJoin(server1);
        registerRoomJoin(server2);
        server1.addEventListener("chatMessage", String.class, (client, message, ack) ->
                server1.getRoomOperations(ROOM).sendEvent("message", message));

        server1.start();
        server2.start();

        userA = TestSocketClient.connect(port1);
        userB = TestSocketClient.connect(port2);
        userA.emit("joinRoom", ROOM);
        userB.emit("joinRoom", ROOM);
        assertNotNull(userA.await(frame -> frame.startsWith("42[\"joined\"")));
        assertNotNull(userB.await(frame -> frame.startsWith("42[\"joined\"")));

        userA.emit("chatMessage", "hello-from-be1");

        assertEquals(
                "42[\"message\",\"hello-from-be1\"]",
                userB.await(frame -> frame.startsWith("42[\"message\"")));
    }

    private static void registerRoomJoin(SocketIOServer server) {
        server.addEventListener("joinRoom", String.class, (client, room, ack) -> {
            client.joinRoom(room);
            client.sendEvent("joined", room);
        });
    }

    private static SocketIOServer createServer(int port) {
        org.redisson.config.Config redisConfig = new org.redisson.config.Config();
        redisConfig.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ':' + REDIS.getMappedPort(6379));
        RedissonClient redisson = Redisson.create(redisConfig);

        Configuration socketConfig = new Configuration();
        socketConfig.setHostname("127.0.0.1");
        socketConfig.setPort(port);
        socketConfig.setStoreFactory(new RedissonStoreFactory(redisson));
        return new SocketIOServer(socketConfig);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class TestSocketClient implements WebSocket.Listener {

        private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder partialFrame = new StringBuilder();
        private final java.util.concurrent.CountDownLatch connected =
                new java.util.concurrent.CountDownLatch(1);
        private volatile WebSocket webSocket;

        static TestSocketClient connect(int port) throws Exception {
            TestSocketClient listener = new TestSocketClient();
            listener.webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(WAIT_TIMEOUT)
                    .buildAsync(
                            URI.create("ws://127.0.0.1:" + port
                                    + "/socket.io/?EIO=4&transport=websocket"),
                            listener)
                    .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!listener.connected.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Socket.IO namespace connection timed out");
            }
            return listener;
        }

        void emit(String event, String value) {
            webSocket.sendText(
                    "42[\"" + event + "\",\"" + value + "\"]", true).join();
        }

        String await(Predicate<String> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                String frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && predicate.test(frame)) {
                    return frame;
                }
            }
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialFrame.append(data);
            if (last) {
                String frame = partialFrame.toString();
                partialFrame.setLength(0);
                if (frame.startsWith("0")) {
                    webSocket.sendText("40", true);
                } else if (frame.startsWith("40")) {
                    connected.countDown();
                } else if (frame.equals("2")) {
                    webSocket.sendText("3", true);
                }
                frames.offer(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        void close() {
            WebSocket current = webSocket;
            if (current != null) {
                current.abort();
            }
        }
    }
}
