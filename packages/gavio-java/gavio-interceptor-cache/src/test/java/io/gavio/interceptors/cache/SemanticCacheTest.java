package io.gavio.interceptors.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SemanticCacheTest {

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
    void exactCacheHitSkipsProvider() {
        Counting provider = new Counting("cached answer");
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();

        GavioResponse r1 = gw.complete(req("what is 2 + 2?")).join();
        GavioResponse r2 = gw.complete(req("what is 2 + 2?")).join();

        assertEquals(1, provider.calls.get());
        assertFalse(r1.cacheHit());
        assertTrue(r2.cacheHit());
        assertEquals("exact", r2.cacheType().value());
        assertEquals(0.0, r2.costUsd());
    }

    @Test
    void exactCacheMissOnDifferentPrompt() {
        Counting provider = new Counting("x");
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        gw.complete(req("alpha")).join();
        gw.complete(req("beta")).join();
        assertEquals(2, provider.calls.get());
    }

    @Test
    void semanticCacheHitsOnVariant() {
        Counting provider = new Counting("semantic answer");
        SemanticCache cache = SemanticCache.builder().embedder(new HashingEmbedder()).build();
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(cache).build();

        GavioResponse r1 = gw.complete(req("What is 2+2?")).join();
        GavioResponse r2 = gw.complete(req("what is   2 + 2 ?")).join();

        assertEquals(1, provider.calls.get());
        assertFalse(r1.cacheHit());
        assertTrue(r2.cacheHit());
        assertEquals("semantic", r2.cacheType().value());
    }

    @Test
    void semanticDisabledWithoutEmbedder() {
        Counting provider = new Counting("x");
        Gateway gw = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        gw.complete(req("What is 2+2?")).join();
        gw.complete(req("what is 2 + 2 ?")).join();
        assertEquals(2, provider.calls.get());
    }

    @Test
    void embedderAndCosine() {
        HashingEmbedder e = new HashingEmbedder();
        double[] a = e.embed("What is 2+2?");
        double[] b = e.embed("what is   2 + 2 ?");
        double[] c = e.embed("completely different sentence about cats");
        assertTrue(Vectors.cosine(a, b) > 0.99);
        assertTrue(Vectors.cosine(a, c) < 0.5);
    }

    @Test
    void memoryBackendEvicts() {
        MemoryCacheBackend backend = new MemoryCacheBackend(2);
        backend.set("k1", "v1", null);
        assertEquals("v1", backend.get("k1"));
        backend.set("k2", "v2", null);
        backend.set("k3", "v3", null);
        assertNull(backend.get("k1"));
        assertEquals("v3", backend.get("k3"));
    }
}
