package io.gavio.exporters;

import io.gavio.inspector.InspectorEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Privacy helpers for runtime event export. */
public final class RuntimeEventPrivacy {

    private static final Set<String> CONTENT_KEYS = Set.of("messages", "content", "diff");

    private RuntimeEventPrivacy() {}

    /** Return an event map with content-bearing fields removed. */
    public static Map<String, Object> metadataOnly(InspectorEvent event) {
        Map<String, Object> out = new LinkedHashMap<>(event.toMap());
        Object data = out.get("data");
        if (data instanceof Map<?, ?> map) {
            out.put("data", stripMap(map));
        }
        return out;
    }

    private static Map<String, Object> stripMap(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : in.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (CONTENT_KEYS.contains(key)) {
                continue;
            }
            out.put(key, strip(entry.getValue()));
        }
        return out;
    }

    private static Object strip(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stripMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(strip(item));
            }
            return out;
        }
        return value;
    }
}
