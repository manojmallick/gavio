package io.gavio.interceptors.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** LRU-bounded, optionally TTL'd in-process cache (F-CACHE-03). */
public final class MemoryCacheBackend implements CacheBackend {

    private record Slot(Object value, Long expiresAtMs) {}

    private final int maxSize;
    private final LinkedHashMap<String, Slot> store;

    public MemoryCacheBackend() {
        this(1000);
    }

    public MemoryCacheBackend(int maxSize) {
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
                return size() > MemoryCacheBackend.this.maxSize;
            }
        };
    }

    @Override
    public synchronized Object get(String key) {
        Slot e = store.get(key);
        if (e == null) {
            return null;
        }
        if (e.expiresAtMs() != null && System.currentTimeMillis() > e.expiresAtMs()) {
            store.remove(key);
            return null;
        }
        return e.value();
    }

    @Override
    public synchronized void set(String key, Object value, Long ttlSeconds) {
        Long expires = ttlSeconds == null ? null : System.currentTimeMillis() + ttlSeconds * 1000;
        store.put(key, new Slot(value, expires));
    }

    @Override
    public synchronized void delete(String key) {
        store.remove(key);
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }

    public synchronized int size() {
        return store.size();
    }
}
