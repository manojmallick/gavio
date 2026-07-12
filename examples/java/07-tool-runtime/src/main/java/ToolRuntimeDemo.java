import io.gavio.Gateway;
import io.gavio.GavioException.ToolRuntimeException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor.OnFailure;
import io.gavio.providers.MockProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * Gavio Tool Runtime - validate tool results before model context reuse.
 *
 * <pre>{@code mvn -q compile exec:java}</pre>
 */
public class ToolRuntimeDemo {

    public static void main(String[] args) {
        Map<String, Object> freshConflictTools = Map.of(
                "now", "2026-07-12T12:00:30Z",
                "conflict_keys", List.of("delivery_date"),
                "calls", List.of(
                        Map.of(
                                "id", "ship-a",
                                "name", "shipping",
                                "source", "carrier-a",
                                "created_at", "2026-07-12T12:00:00Z",
                                "confidence", 0.8,
                                "result", Map.of("delivery_date", "Monday")),
                        Map.of(
                                "id", "ship-b",
                                "name", "shipping",
                                "source", "carrier-b",
                                "created_at", "2026-07-12T12:00:00Z",
                                "confidence", 0.7,
                                "result", Map.of("delivery_date", "Wednesday"))));
        Map<String, Object> staleTools = Map.of(
                "now", "2026-07-12T12:03:00Z",
                "max_age_seconds", 60,
                "calls", List.of(Map.of(
                        "id", "price-1",
                        "name", "price",
                        "source", "pricing-cache",
                        "created_at", "2026-07-12T12:00:00Z",
                        "result", Map.of("sku", "SKU-3", "price", 9.99),
                        "output_schema", Map.of(
                                "required", List.of("sku", "price"),
                                "properties", Map.of("sku", "string", "price", "number")))));

        Map<String, Object> decision = ToolRuntimeInterceptor.analyze(freshConflictTools);
        System.out.println("conflicts : " + decision.get("conflicts"));
        System.out.println("confidence: " + decision.get("confidence"));
        System.out.println("provenance: " + decision.get("provenance"));

        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("ok"))
                .model("mock")
                .use(ToolRuntimeInterceptor.builder().onFailure(OnFailure.ERROR).build())
                .build();

        try {
            gw.complete(GavioRequest.builder()
                    .message("user", "reuse the cached price quote")
                    .metadata("tools", staleTools)
                    .build()).join();
        } catch (CompletionException exc) {
            if (!(exc.getCause() instanceof ToolRuntimeException)) {
                throw exc;
            }
            System.out.println("blocked  : " + exc.getCause().getMessage());
        }
    }
}
