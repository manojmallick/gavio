package io.gavio.interceptors.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AuditChainTraceTest {

    private static final class Collector implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.add(record);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static GavioRequest req(String content) {
        return GavioRequest.builder().message("user", content).model("mock").build();
    }

    @Test
    void hashChainLinksAndVerifies() {
        Collector sink = new Collector();
        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(AuditInterceptor.builder().sink(sink).hashChain(true).build())
                .build();
        for (int i = 0; i < 3; i++) {
            gw.complete(req("m" + i)).join();
        }
        assertEquals(3, sink.records.size());
        assertEquals("", sink.records.get(0).previousHash());
        assertTrue(AuditChain.verifyChain(sink.records));
    }

    @Test
    void multiAgentDagReconstruction() {
        Collector sink = new Collector();
        Gateway gw = Gateway.builder().devMode(true).use(AuditInterceptor.builder().sink(sink).build()).build();
        GavioResponse root = gw.complete(
                GavioRequest.builder().message("user", "orchestrate").model("mock").agentId("orchestrator").build())
                .join();
        gw.complete(GavioRequest.builder().message("user", "a").model("mock").agentId("agent-a")
                .parentTraceId(root.traceId()).build()).join();
        gw.complete(GavioRequest.builder().message("user", "b").model("mock").agentId("agent-b")
                .parentTraceId(root.traceId()).build()).join();

        List<AuditTrace.TraceNode> roots = AuditTrace.buildCallGraph(sink.records);
        assertEquals(1, roots.size());
        assertEquals("orchestrator", roots.get(0).agentId);
        Set<String> children =
                roots.get(0).children.stream().map(n -> n.agentId).collect(Collectors.toSet());
        assertEquals(Set.of("agent-a", "agent-b"), children);
    }

    @Test
    void chainVerifiesFalseWhenBroken() {
        Collector sink = new Collector();
        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(AuditInterceptor.builder().sink(sink).hashChain(true).build())
                .build();
        for (int i = 0; i < 3; i++) {
            gw.complete(req("m" + i)).join();
        }
        // Drop the middle record — chain must break.
        List<AuditRecord> broken = new ArrayList<>(sink.records);
        broken.remove(1);
        assertFalse(AuditChain.verifyChain(broken));
    }
}
