package io.gavio.controlplane;

import io.gavio.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fetches and caches runtime configuration from the self-hosted control plane. */
public final class ControlPlaneClient {

    private final ControlPlaneOptions options;
    private final HttpClient httpClient;
    private final Path cachePath;

    public ControlPlaneClient(ControlPlaneOptions options) {
        this.options = options;
        this.httpClient = HttpClient.newHttpClient();
        this.cachePath = options.cachePath() != null
                ? options.cachePath()
                : defaultCachePath(options.url(), options.policySource());
    }

    public Map<String, Object> loadConfig() {
        try {
            Map<String, Object> config = fetchConfig();
            markLoadedFrom(config, "control_plane");
            writeCache(config);
            return config;
        } catch (RuntimeException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Map<String, Object> cached = readCache();
            if (cached != null) {
                markLoadedFrom(cached, "cache");
                return cached;
            }
            if ("closed".equals(options.failMode())) {
                throw new ControlPlaneException(
                        "failed to load control-plane config for " + options.policySource(), e);
            }
            return unavailableConfig(options.policySource(), options.failMode());
        }
    }

    private Map<String, Object> fetchConfig() throws IOException, InterruptedException {
        String base = options.url().replaceAll("/+$", "");
        String query = "policy_source=" + encode(options.policySource())
                + "&fail_mode=" + encode(options.failMode());
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/runtime/config?" + query))
                .timeout(Duration.ofMillis(options.timeoutMillis()))
                .header("Authorization", "Bearer " + options.runtimeKey())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ControlPlaneException("control plane returned HTTP " + response.statusCode());
        }
        return Json.parseObject(response.body());
    }

    private Map<String, Object> readCache() {
        try {
            if (!Files.exists(cachePath)) {
                return null;
            }
            return Json.parseObject(Files.readString(cachePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void writeCache(Map<String, Object> config) {
        try {
            Path parent = cachePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(cachePath, Json.write(config) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write control-plane cache", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void markLoadedFrom(Map<String, Object> config, String source) {
        Object raw = config.get("cache");
        Map<String, Object> cache;
        if (raw instanceof Map<?, ?> map) {
            cache = (Map<String, Object>) map;
        } else {
            cache = new LinkedHashMap<>();
            config.put("cache", cache);
        }
        cache.put("loadedFrom", source);
    }

    private static Map<String, Object> unavailableConfig(String policySource, String failMode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemaVersion", "1.0");
        config.put("configVersion", "unavailable");
        config.put("projectId", "");
        config.put("environment", "");
        config.put("policySource", policySource);
        config.put("policy", Map.of("id", "unavailable", "name", "unavailable", "rules", List.of()));
        config.put("budgets", List.of());
        config.put("rollout", Map.of("id", "unavailable", "policyId", "unavailable", "status", "paused"));
        config.put("cache", Map.of("ttlSeconds", 0, "failMode", failMode, "loadedFrom", "unavailable"));
        return config;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Path defaultCachePath(String url, String policySource) {
        String root = System.getenv("GAVIO_CACHE_DIR");
        Path base = root == null || root.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache", "gavio")
                : Path.of(root);
        return base.resolve("control-plane").resolve(sha256(url + "|" + policySource).substring(0, 16) + ".json");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
