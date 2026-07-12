import io.gavio.Gateway;
import io.gavio.controlplane.ControlPlaneOptions;
import java.nio.file.Path;
import java.util.Map;

public class ControlPlaneDemo {
  public static void main(String[] args) {
    String url = System.getenv().getOrDefault("GAVIO_CONTROL_PLANE_URL", "http://127.0.0.1:8787");
    String key = System.getenv().getOrDefault("GAVIO_RUNTIME_KEY", "gav_rt_missing");
    String source = System.getenv().getOrDefault("GAVIO_POLICY_SOURCE", "project:prod-support");

    Gateway gateway = Gateway.builder()
        .devMode(true)
        .controlPlane(ControlPlaneOptions.builder(url, key, source)
            .cachePath(Path.of(".gavio-control-plane-cache.json"))
            .timeoutMillis(200)
            .failMode("open")
            .build())
        .build();

    Map<String, Object> config = gateway.controlPlaneConfig();
    @SuppressWarnings("unchecked")
    Map<String, Object> cache = (Map<String, Object>) config.get("cache");
    System.out.println("source : " + cache.get("loadedFrom"));
    System.out.println("policy : " + config.get("policySource"));
    System.out.println("project: " + (String.valueOf(config.get("projectId")).isBlank()
        ? "(not loaded)"
        : config.get("projectId")));
  }
}
