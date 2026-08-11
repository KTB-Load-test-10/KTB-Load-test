package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.store.StoreFactory;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Application-level Socket.IO state backed by the configured netty-socketio store factory.
 * A MemoryStoreFactory keeps it local; a RedissonStoreFactory makes the named map shared.
 */
public class StoreFactoryChatDataStore implements ChatDataStore {

    private final Map<String, Object> storage;

    public StoreFactoryChatDataStore(StoreFactory storeFactory, String mapName) {
        this.storage = storeFactory.createMap(mapName);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }

    @Override
    public void delete(String key) {
        storage.remove(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addToSet(String key, String value) {
        storage.compute(key, (ignored, current) -> {
            Set<String> values = current instanceof Set<?>
                    ? new HashSet<>((Set<String>) current)
                    : new HashSet<>();
            values.add(value);
            return values;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void removeFromSet(String key, String value) {
        storage.computeIfPresent(key, (ignored, current) -> {
            if (!(current instanceof Set<?>)) {
                return null;
            }
            Set<String> values = new HashSet<>((Set<String>) current);
            values.remove(value);
            return values.isEmpty() ? null : values;
        });
    }

    @Override
    public int size() {
        return storage.size();
    }
}
