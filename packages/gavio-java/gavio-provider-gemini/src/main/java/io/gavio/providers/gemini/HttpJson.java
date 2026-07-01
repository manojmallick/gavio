package io.gavio.providers.gemini;

import io.gavio.GavioException.ProviderUnavailableException;
import io.gavio.GavioException.RateLimitException;
import io.gavio.GavioException.ServerException;
import io.gavio.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tiny async JSON-over-HTTP helper on {@link HttpClient}.
 *
 * <p>Maps HTTP status families onto Gavio's transient error types so the
 * retry/fallback policies can react: 429 -> RateLimit, 5xx -> Server, else
 * Unavailable.
 */
final class HttpJson {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpJson() {
    }

    static CompletableFuture<Map<String, Object>> postJson(
            String url, Map<String, Object> payload, Map<String, String> headers, double timeoutSeconds) {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)));
        headers.forEach(builder::header);

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        throw new ProviderUnavailableException("network error: " + cause.getMessage(), cause);
                    }
                    int code = response.statusCode();
                    String body = response.body();
                    if (code == 429) {
                        throw new RateLimitException("429 from provider: " + truncate(body));
                    }
                    if (code >= 500) {
                        throw new ServerException(code + " from provider: " + truncate(body));
                    }
                    if (code >= 400) {
                        throw new ProviderUnavailableException(code + " from provider: " + truncate(body));
                    }
                    return Json.parseObject(body);
                });
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
