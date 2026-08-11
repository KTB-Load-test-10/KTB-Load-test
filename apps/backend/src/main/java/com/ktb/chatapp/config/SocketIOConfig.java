package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.corundumstudio.socketio.store.StoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.StoreFactoryChatDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.util.StringUtils;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${socketio.server.origin:*}")
    private String origin;

    @Value("${socketio.server.accept-backlog:128}")
    private int acceptBacklog;

    @Value("${socketio.server.tcp-no-delay:true}")
    private boolean tcpNoDelay;

    @Value("${socketio.redis.host:}")
    private String socketRedisHost;

    @Value("${socketio.redis.port:6379}")
    private int socketRedisPort;

    @Value("${socketio.redis.password:}")
    private String socketRedisPassword;

    @Bean
    public StoreFactory socketIOStoreFactory() {
        if (!StringUtils.hasText(socketRedisHost)) {
            log.warn("SOCKET_REDIS_HOST is not configured; Socket.IO is using a single-node in-memory store");
            return new MemoryStoreFactory();
        }

        Config redissonConfig = new Config();
        var singleServer = redissonConfig.useSingleServer()
                .setAddress("redis://" + socketRedisHost + ':' + socketRedisPort)
                .setConnectTimeout(3_000)
                .setTimeout(3_000)
                .setRetryAttempts(1);
        if (StringUtils.hasText(socketRedisPassword)) {
            singleServer.setPassword(socketRedisPassword);
        }

        RedissonClient redissonClient = null;
        try {
            redissonClient = Redisson.create(redissonConfig);
            // Redisson creates connections lazily in some configurations. Force a command so a
            // configured but unreachable Socket Redis fails application startup immediately.
            redissonClient.getBucket("chatapp:socketio:startup-check").isExists();
            log.info("Socket.IO distributed Redis store connected: {}:{}",
                    socketRedisHost, socketRedisPort);
            return new RedissonStoreFactory(redissonClient);
        } catch (Exception e) {
            if (redissonClient != null) {
                redissonClient.shutdown();
            }
            throw new IllegalStateException(
                    "Failed to connect to dedicated Socket.IO Redis at "
                            + socketRedisHost + ':' + socketRedisPort,
                    e);
        }
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(
            AuthTokenListener authTokenListener,
            MeterRegistry meterRegistry,
            StoreFactory socketIOStoreFactory) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);
        
        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(tcpNoDelay);
        socketConfig.setAcceptBackLog(acceptBacklog);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin(origin);

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(socketIOStoreFactory);

        log.info("Socket.IO server configured on {}:{} with store={}, transports={}",
                host, port, socketIOStoreFactory.getClass().getSimpleName(), config.getTransports());
        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addEventInterceptor((client, name, data, ack) -> {
            // 이벤트 발생 빈도 수집
            Counter.builder("socketio.events.total")
                .description("Total Socket.IO events received")
                .tag("event_type", name)
                .register(meterRegistry)
                .increment();
        });
        
        return socketIOServer;
    }
    
    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
    
    @Bean("connectedUsersStore")
    public ChatDataStore connectedUsersStore(StoreFactory socketIOStoreFactory) {
        return new StoreFactoryChatDataStore(
                socketIOStoreFactory, "chatapp:socketio:connected-users");
    }

    @Bean("userRoomsStore")
    public ChatDataStore userRoomsStore(StoreFactory socketIOStoreFactory) {
        return new StoreFactoryChatDataStore(
                socketIOStoreFactory, "chatapp:socketio:user-rooms");
    }
}
