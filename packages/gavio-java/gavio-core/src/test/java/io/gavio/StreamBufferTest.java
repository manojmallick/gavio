package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.MockProvider;
import io.gavio.providers.StreamBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Streaming reliability / StreamBuffer (F-REL-06). */
class StreamBufferTest {

    /** A post-interceptor that rewrites the response content. */
    private static final class Shout implements Interceptor {
        @Override
        public String name() {
            return "shout";
        }

        @Override
        public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
            return CompletableFuture.completedFuture(response.withContent(response.content().toUpperCase()));
        }
    }

    /** A post-interceptor that records the response content it observed. */
    private static final class Capture implements Interceptor {
        final AtomicReference<String> seen = new AtomicReference<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
            seen.set(response.content());
            return CompletableFuture.completedFuture(response);
        }
    }

    private static String drain(java.util.concurrent.Flow.Publisher<String> publisher) {
        return StreamBuffer.collect(publisher).join().text();
    }

    private static GavioRequest req(String content) {
        return GavioRequest.builder().message("user", content).model("mock").build();
    }

    @Test
    void streamBufferAccumulates() {
        StreamBuffer buf = new StreamBuffer();
        assertEquals("", buf.text());
        assertEquals(0, buf.length());
        buf.append("ab");
        buf.append("cd");
        assertEquals("abcd", buf.text());
        assertEquals(4, buf.length());
    }

    @Test
    void collectAssemblesProviderStream() {
        String text = StreamBuffer.collect(new MockProvider().stream(req("hi there"))).join().text();
        assertEquals("[mock reply] hi there", text.trim());
    }

    @Test
    void gatewayStreamEmitsBufferedContent() {
        Gateway gw = Gateway.builder().devMode(true).build();
        String full = drain(gw.stream(req("hi there")));
        assertEquals("[mock reply] hi there", full.trim());
    }

    @Test
    void postInterceptorSeesFullBufferedResponse() {
        Capture capture = new Capture();
        Gateway gw = Gateway.builder().devMode(true).use(capture).build();
        String full = drain(gw.stream(req("hello world")));
        assertEquals(full, capture.seen.get());
        assertTrue(full.contains("hello world"));
    }

    @Test
    void postInterceptorRewriteVisibleToCaller() {
        Gateway gw = Gateway.builder().devMode(true).use(new Shout()).build();
        String full = drain(gw.stream(req("quiet")));
        assertEquals(full.toUpperCase(), full);
        assertTrue(full.contains("[MOCK REPLY] QUIET"));
    }
}
