package io.gavio.interceptors.audit.sinks;

import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.audit.AuditSink;
import io.gavio.json.Json;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Print each audit record. Human-readable audit output for development (F-OBS-05). */
public final class StdoutSink implements AuditSink {

    private final boolean pretty;
    private final PrintStream stream;

    public StdoutSink() {
        this(true, System.out);
    }

    public StdoutSink(boolean pretty) {
        this(pretty, System.out);
    }

    public StdoutSink(boolean pretty, PrintStream stream) {
        this.pretty = pretty;
        this.stream = stream;
    }

    @Override
    public CompletableFuture<Void> write(AuditRecord record) {
        Map<String, Object> data = record.toMap();
        String line = pretty ? formatPretty(data) : Json.write(data);
        stream.println(line);
        stream.flush();
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    private static String formatPretty(Map<String, Object> data) {
        Map<String, Object> usage = (Map<String, Object>) data.get("token_usage");
        List<String> piiList = (List<String>) data.get("pii_entity_types");
        String pii = (piiList == null || piiList.isEmpty()) ? "none" : String.join(",", piiList);
        List<String> fired = (List<String>) data.get("interceptors_fired");
        String traceId = String.valueOf(data.get("trace_id"));
        String traceShort = traceId.length() > 18 ? traceId.substring(0, 18) : traceId;
        return "[gavio:audit] "
                + "trace=" + traceShort + "… "
                + data.get("provider") + "/" + data.get("model") + " "
                + "tokens=" + usage.get("total_tokens") + " "
                + String.format("cost=$%.6f ", ((Number) data.get("cost_usd")).doubleValue())
                + "latency=" + data.get("latency_ms") + "ms "
                + "cache=" + (Boolean.TRUE.equals(data.get("cache_hit")) ? "HIT" : "miss") + " "
                + "pii=" + pii + " "
                + "interceptors=[" + String.join(",", fired) + "]";
    }
}
