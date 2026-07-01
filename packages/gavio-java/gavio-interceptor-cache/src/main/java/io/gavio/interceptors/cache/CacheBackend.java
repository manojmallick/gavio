package io.gavio.interceptors.cache;

/** A minimal key/value store behind the cache interceptors (F-CACHE-03). */
public interface CacheBackend {
    Object get(String key);

    void set(String key, Object value, Long ttlSeconds);

    void delete(String key);

    void clear();
}
