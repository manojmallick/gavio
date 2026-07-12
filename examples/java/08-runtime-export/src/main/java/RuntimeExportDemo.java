import io.gavio.Gateway;
import io.gavio.exporters.JsonlRuntimeExporter;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.json.Json;
import io.gavio.types.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gavio Runtime Export - metadata-safe JSONL event export. */
public class RuntimeExportDemo {
    public static void main(String[] args) {
        List<String> lines = new ArrayList<>();
        Gateway gateway = Gateway.builder()
                .devMode(true)
                .use(new PiiGuard())
                .exporter(new JsonlRuntimeExporter(lines::add))
                .build();

        gateway.complete(List.of(Message.of("user", "Email jan@example.com about ACME billing"))).join();

        List<Map<String, Object>> events = lines.stream().map(Json::parseObject).toList();
        boolean leaked = events.stream().anyMatch(event -> {
            String data = Json.write(event.get("data"));
            return data.contains("\"messages\"")
                    || data.contains("\"content\"")
                    || data.contains("\"diff\"");
        });

        System.out.println("exported_events=" + events.size());
        System.out.println("event_types=" + events.stream().map(event -> event.get("type")).toList());
        System.out.println("content_keys_exported=" + leaked);
    }
}
