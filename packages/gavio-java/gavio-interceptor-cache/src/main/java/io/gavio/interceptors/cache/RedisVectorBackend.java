package io.gavio.interceptors.cache;

import io.gavio.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brute-force cosine-similarity {@link VectorBackend} over Redis (F-CACHE-04).
 *
 * <p>Same matching strategy as {@link InMemoryVectorBackend} — just shared
 * across processes. Fine for the cache's scale (bounded, TTL'd entries); not a
 * substitute for a real vector database.
 */
public final class RedisVectorBackend implements VectorBackend {

    private final RespClient client;
    private final String namespace;
    private final String indexKey;

    public RedisVectorBackend() {
        this("redis://localhost:6379", "gavio:vector");
    }

    public RedisVectorBackend(String url) {
        this(url, "gavio:vector");
    }

    public RedisVectorBackend(String url, String namespace) {
        RedisUrl parsed = RedisUrl.parse(url);
        this.client = new RespClient(parsed.host(), parsed.port());
        this.namespace = namespace;
        this.indexKey = namespace + ":index";
    }

    private String entryKey(String id) {
        return namespace + ":" + id;
    }

    @Override
    public void add(double[] vector, Object value, Long ttlSeconds) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vector", boxVector(vector));
        payload.put("value", JsonableValues.toJsonable(value));
        String raw = Json.write(payload);
        if (ttlSeconds != null) {
            client.command("SET", entryKey(id), raw, "EX", ttlSeconds);
        } else {
            client.command("SET", entryKey(id), raw);
        }
        client.command("SADD", indexKey, id);
    }

    @Override
    public Object query(double[] vector, double threshold) {
        List<String> ids = RedisCacheBackend.asStringList(client.command("SMEMBERS", indexKey));
        Object best = null;
        double bestSim = threshold;
        for (String id : ids) {
            Object raw = client.command("GET", entryKey(id));
            if (!(raw instanceof String s)) {
                client.command("SREM", indexKey, id);
                continue;
            }
            Map<String, Object> entry = Json.parseObject(s);
            double[] entryVector = unboxVector((List<?>) entry.get("vector"));
            double sim = Vectors.cosine(vector, entryVector);
            if (sim >= bestSim) {
                bestSim = sim;
                best = entry.get("value");
            }
        }
        return best;
    }

    @Override
    public void clear() {
        List<String> ids = RedisCacheBackend.asStringList(client.command("SMEMBERS", indexKey));
        if (!ids.isEmpty()) {
            Object[] args = new Object[ids.size() + 1];
            args[0] = "DEL";
            for (int i = 0; i < ids.size(); i++) {
                args[i + 1] = entryKey(ids.get(i));
            }
            client.command(args);
        }
        client.command("DEL", indexKey);
    }

    private static List<Double> boxVector(double[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) {
            out.add(d);
        }
        return out;
    }

    private static double[] unboxVector(List<?> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = ((Number) list.get(i)).doubleValue();
        }
        return out;
    }
}
