package io.gavio.interceptors.cache;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts arbitrary cache values — including the private {@code CacheEntry}
 * record {@link SemanticCache} stores — into JSON-safe structures generically,
 * via the record component reflection API, so backends never need to know
 * about specific value types.
 */
final class JsonableValues {

    private JsonableValues() {
    }

    static Object toJsonable(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), toJsonable(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonableValues::toJsonable).toList();
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : value.getClass().getRecordComponents()) {
                try {
                    out.put(rc.getName(), toJsonable(rc.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "failed to serialize record component " + rc.getName(), e);
                }
            }
            return out;
        }
        return String.valueOf(value);
    }
}
