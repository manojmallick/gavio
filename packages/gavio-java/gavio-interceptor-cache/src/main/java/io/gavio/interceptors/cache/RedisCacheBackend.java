package io.gavio.interceptors.cache;

import io.gavio.json.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact-match {@link CacheBackend} over Redis (F-CACHE-04).
 *
 * <p>Keys are namespaced under an index set so {@link #clear()} only removes
 * entries this backend itself wrote, never the whole database.
 */
public final class RedisCacheBackend implements CacheBackend {

    private final RespClient client;
    private final String prefix;
    private final String indexKey;

    public RedisCacheBackend() {
        this("redis://localhost:6379", "gavio:cache");
    }

    public RedisCacheBackend(String url) {
        this(url, "gavio:cache");
    }

    public RedisCacheBackend(String url, String namespace) {
        RedisUrl parsed = RedisUrl.parse(url);
        this.client = new RespClient(parsed.host(), parsed.port());
        this.prefix = namespace + ":";
        this.indexKey = namespace + ":index";
    }

    private String namespaced(String key) {
        return prefix + key;
    }

    @Override
    public Object get(String key) {
        Object raw = client.command("GET", namespaced(key));
        if (!(raw instanceof String s)) {
            client.command("SREM", indexKey, key);
            return null;
        }
        return Json.parse(s);
    }

    @Override
    public void set(String key, Object value, Long ttlSeconds) {
        String raw = Json.write(JsonableValues.toJsonable(value));
        if (ttlSeconds != null) {
            client.command("SET", namespaced(key), raw, "EX", ttlSeconds);
        } else {
            client.command("SET", namespaced(key), raw);
        }
        client.command("SADD", indexKey, key);
    }

    @Override
    public void delete(String key) {
        client.command("DEL", namespaced(key));
        client.command("SREM", indexKey, key);
    }

    @Override
    public void clear() {
        List<String> keys = asStringList(client.command("SMEMBERS", indexKey));
        if (!keys.isEmpty()) {
            Object[] args = new Object[keys.size() + 1];
            args[0] = "DEL";
            for (int i = 0; i < keys.size(); i++) {
                args[i + 1] = namespaced(keys.get(i));
            }
            client.command(args);
        }
        client.command("DEL", indexKey);
    }

    static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
