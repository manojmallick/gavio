package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gavio.interceptors.Executor;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorChain;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class InterceptorChainTest {

    private static GavioRequest req() {
        return GavioRequest.builder()
                .message("user", "hi")
                .model("mock")
                .provider(Provider.MOCK)
                .build();
    }

    private static GavioResponse resp() {
        return GavioResponse.builder().traceId("t").content("ok").model("mock").provider("mock").build();
    }

    private static final class Recorder implements Interceptor {
        private final String name;
        private final List<String> log;

        Recorder(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
            log.add(name + ".before");
            return CompletableFuture.completedFuture(request);
        }

        @Override
        public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
            log.add(name + ".after");
            return CompletableFuture.completedFuture(response);
        }
    }

    @Test
    void onionOrdering() {
        List<String> log = new ArrayList<>();
        InterceptorChain chain = new InterceptorChain(List.of(
                new Recorder("a", log), new Recorder("b", log)));

        Executor executor = request -> {
            log.add("provider");
            return CompletableFuture.completedFuture(resp());
        };

        chain.execute(req(), new InterceptorContext("t"), executor).join();
        assertEquals(List.of("a.before", "b.before", "provider", "b.after", "a.after"), log);
    }

    @Test
    void interceptorsFiredRecorded() {
        List<String> log = new ArrayList<>();
        InterceptorChain chain = new InterceptorChain(List.of(new Recorder("a", log)));
        Executor executor = request -> CompletableFuture.completedFuture(resp());
        GavioResponse out = chain.execute(req(), new InterceptorContext("t"), executor).join();
        assertEquals(List.of("a"), out.interceptorsFired());
    }
}
