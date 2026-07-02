package io.gavio.interceptors.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Redis cache backends (F-CACHE-04).
 *
 * <p>Skipped automatically when no Redis server is reachable — set
 * {@code GAVIO_TEST_REDIS_URL} to point at a non-default instance (default:
 * {@code redis://localhost:6379}, matching the CI service container).
 */
class RedisCacheBackendTest {

    private static final String REDIS_URL =
            System.getenv().getOrDefault("GAVIO_TEST_REDIS_URL", "redis://localhost:6379");

    @BeforeEach
    void requireRedis() {
        assumeTrue(redisAvailable(), "redis not reachable at " + REDIS_URL);
    }

    private static boolean redisAvailable() {
        URI uri = URI.create(REDIS_URL);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort() != -1 ? uri.getPort() : 6379;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String ns() {
        return "gavio:test:" + UUID.randomUUID();
    }

    private static GavioRequest req(String content) {
        return GavioRequest.builder().message("user", content).model("mock").build();
    }

    /** Counting adapter that wraps a MockProvider (MockProvider is final). */
    private static final class Counting implements ProviderAdapter {
        final AtomicInteger calls = new AtomicInteger();
        private final MockProvider delegate;

        Counting(String response) {
            this.delegate = new MockProvider(response);
        }

        @Override
        public String providerName() {
            return delegate.providerName();
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            calls.incrementAndGet();
            return delegate.complete(request);
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return delegate.healthCheck();
        }
    }

    @Test
    void directRoundtrip() {
        RedisCacheBackend backend = new RedisCacheBackend(REDIS_URL, ns());
        backend.set("k1", "v1", null);
        assertEquals("v1", backend.get("k1"));
        backend.delete("k1");
        assertNull(backend.get("k1"));
    }

    @Test
    void ttlExpiry() throws InterruptedException {
        RedisCacheBackend backend = new RedisCacheBackend(REDIS_URL, ns());
        backend.set("k1", "v1", 1L);
        assertEquals("v1", backend.get("k1"));
        Thread.sleep(1300);
        assertNull(backend.get("k1"));
    }

    @Test
    void clearOnlyRemovesOwnNamespace() {
        RedisCacheBackend backend = new RedisCacheBackend(REDIS_URL, ns());
        RedisCacheBackend other = new RedisCacheBackend(REDIS_URL, ns());
        backend.set("a", "1", null);
        backend.set("b", "2", null);
        other.set("c", "3", null);
        backend.clear();
        assertNull(backend.get("a"));
        assertNull(backend.get("b"));
        assertEquals("3", other.get("c"));
        other.clear();
    }

    @Test
    void vectorQueryAndClear() {
        RedisVectorBackend vector = new RedisVectorBackend(REDIS_URL, ns());
        vector.add(new double[] {1.0, 0.0}, "a", null);
        vector.add(new double[] {0.0, 1.0}, "b", null);
        assertEquals("a", vector.query(new double[] {1.0, 0.0}, 0.9));
        assertNull(vector.query(new double[] {0.0, -1.0}, 0.9));
        vector.clear();
        assertNull(vector.query(new double[] {1.0, 0.0}, 0.0));
    }

    @Test
    void semanticCacheWithRedisBackendExactHit() {
        Counting provider = new Counting("cached via redis");
        SemanticCache cache =
                SemanticCache.builder().backend(new RedisCacheBackend(REDIS_URL, ns())).build();
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(cache).build();

        GavioResponse r1 = gw.complete(req("what is 2 + 2?")).join();
        GavioResponse r2 = gw.complete(req("what is 2 + 2?")).join();

        assertEquals(1, provider.calls.get());
        assertFalse(r1.cacheHit());
        assertTrue(r2.cacheHit());
        assertEquals("exact", r2.cacheType().value());
        assertEquals(r1.content(), r2.content());
    }

    @Test
    void semanticCacheWithRedisBackendsSemanticHit() {
        Counting provider = new Counting("semantic via redis");
        String namespace = ns();
        SemanticCache cache =
                SemanticCache.builder()
                        .backend(new RedisCacheBackend(REDIS_URL, namespace + ":exact"))
                        .embedder(new HashingEmbedder())
                        .vectorBackend(new RedisVectorBackend(REDIS_URL, namespace + ":vector"))
                        .build();
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(cache).build();

        GavioResponse r1 = gw.complete(req("What is 2+2?")).join();
        GavioResponse r2 = gw.complete(req("what is   2 + 2 ?")).join();

        assertEquals(1, provider.calls.get());
        assertFalse(r1.cacheHit());
        assertTrue(r2.cacheHit());
        assertEquals("semantic", r2.cacheType().value());
    }
}
