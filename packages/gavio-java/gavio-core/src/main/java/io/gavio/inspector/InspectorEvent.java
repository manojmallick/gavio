package io.gavio.inspector;

import io.gavio.Ids;
import io.gavio.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One span event emitted while a request moves through the interceptor chain
 * (F-DX-09). Matches {@code spec/InspectorEvent.schema.json} — the camelCase
 * envelope is the cross-SDK wire contract.
 *
 * <p>Content gating is structural: in metadata mode the {@link TraceEmitter}
 * never puts {@code messages} / {@code content} / {@code diff} into
 * {@code data}, so those keys cannot appear in any serialized form.
 */
public record InspectorEvent(
        String eventId,
        String traceId,
        String type,
        long tNs,
        int seq,
        Map<String, Object> data) {

    public static final String SCHEMA_VERSION = "1.0";

    public InspectorEvent {
        if (eventId == null) {
            eventId = Ids.uuid7().toString();
        }
        data = data == null ? Map.of() : data;
    }

    /** Create an event with a fresh UUID v7 event id. */
    public static InspectorEvent of(String traceId, String type, long tNs, int seq, Map<String, Object> data) {
        return new InspectorEvent(Ids.uuid7().toString(), traceId, type, tNs, seq, data);
    }

    /** The full envelope as an ordered map, ready for {@link Json#write}. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_VERSION);
        out.put("eventId", eventId);
        out.put("traceId", traceId);
        out.put("type", type);
        out.put("tNs", tNs);
        out.put("seq", seq);
        out.put("data", data);
        return out;
    }

    /** Serialize to the canonical camelCase JSON envelope. */
    public String toJson() {
        return Json.write(toMap());
    }
}
