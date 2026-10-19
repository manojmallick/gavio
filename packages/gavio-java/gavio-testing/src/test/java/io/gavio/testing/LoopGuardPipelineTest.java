package io.gavio.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.LoopGuardException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditChain;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.audit.AuditSink;
import io.gavio.interceptors.reliability.LoopGuard;
import io.gavio.interceptors.reliability.LoopGuardPolicy;
import io.gavio.interceptors.reliability.LoopGuardPolicy.Action;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Provider;
import io.gavio.types.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Loop guard (F-REL-09) through the audit interceptor: the verdict reaches the audit record as
 * counts only, the key is left out when no guard ran, and hash chains verify either way. The
 * limit semantics are pinned by the shared vectors ({@code LoopGuardVectorsTest}).
 */
class LoopGuardPipelineTest {

    private static final class Scripted implements ProviderAdapter {
        final List<GavioRequest> seen = new ArrayList<>();
        private final MockProvider delegate = new MockProvider("ok");
        private final double costUsd;
        private final List<ToolCall> toolCalls;

        Scripted(double costUsd, List<ToolCall> toolCalls) {
            this.costUsd = costUsd;
            this.toolCalls = toolCalls;
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            seen.add(request);
            return delegate.complete(request).thenApply(r -> GavioResponse.builder()
                    .traceId(r.traceId()).content(r.content()).model(r.model()).provider(r.provider())
                    .modelVersion(r.modelVersion()).usage(r.usage()).costUsd(costUsd)
                    .toolCalls(toolCalls).build());
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class CollectingSink implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.add(record);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static GavioRequest.Builder request() {
        return GavioRequest.builder().message("user", "keep going").model("mock").provider(Provider.MOCK);
    }

    private static List<GavioResponse> agentLoop(Gateway gateway, int calls) {
        List<GavioResponse> responses = new ArrayList<>();
        responses.add(gateway.complete(request().build()).join());
        for (int i = 1; i < calls; i++) {
            responses.add(gateway.complete(request().parentTraceId(responses.get(0).traceId()).build()).join());
        }
        return responses;
    }

    private static Gateway gateway(Scripted provider, LoopGuard guard, CollectingSink sink, boolean hashChain) {
        return Gateway.builder().adapter(provider).model("mock")
                .use(new AuditInterceptor(sink, hashChain)) // outermost
                .use(guard).build();
    }

    @Test
    void theAuditRecordCarriesCountsOnly() {
        CollectingSink sink = new CollectingSink();
        String secret = "jan@example.com";
        Scripted provider = new Scripted(0.25, List.of(new ToolCall("c", "lookup", Map.of("e", secret))));
        Gateway gateway = gateway(provider, LoopGuard.of(LoopGuardPolicy.builder("agent").maxSteps(10).build()),
                sink, false);

        agentLoop(gateway, 3);

        Map<String, Object> third = sink.records.get(2).loopGuard();
        assertEquals(List.of("policy_id", "run_id", "action", "breached", "steps", "cost_usd", "elapsed_seconds"),
                new ArrayList<>(third.keySet()));
        assertEquals("agent", third.get("policy_id"));
        assertEquals(sink.records.get(0).traceId(), third.get("run_id"));
        assertEquals("allow", third.get("action"));
        assertNull(third.get("breached"));
        assertEquals(2, third.get("steps"));
        assertEquals(0.5, (Double) third.get("cost_usd"), 1e-9);
        assertTrue(sink.records.get(2).toMap().containsKey("loop_guard"));
        assertFalse(sink.records.get(2).toJson().contains(secret));
    }

    @Test
    void theAuditRecordHasNoLoopGuardKeyWithoutAGuardAndChainsVerifyEitherWay() {
        CollectingSink plain = new CollectingSink();
        Gateway noGuard = Gateway.builder().adapter(new Scripted(0, List.of())).model("mock")
                .use(new AuditInterceptor(plain, true)).build();
        noGuard.complete(request().build()).join();
        noGuard.complete(request().build()).join();
        assertTrue(plain.records.stream().allMatch(r -> !r.toMap().containsKey("loop_guard")));
        assertTrue(AuditChain.verifyChain(plain.records));

        CollectingSink guarded = new CollectingSink();
        Gateway gateway = gateway(new Scripted(0, List.of()),
                LoopGuard.of(LoopGuardPolicy.builder("agent").maxSteps(10).build()), guarded, true);
        agentLoop(gateway, 3);
        assertTrue(AuditChain.verifyChain(guarded.records));

        // Tampering with the recorded verdict breaks the chain.
        List<AuditRecord> tampered = new ArrayList<>(guarded.records);
        Map<String, Object> forged = new LinkedHashMap<>(tampered.get(1).loopGuard());
        forged.put("steps", 99);
        AuditRecord original = tampered.get(1);
        tampered.set(1, AuditRecord.builder()
                .traceId(original.traceId()).provider(original.provider()).model(original.model())
                .timestampUtc(original.timestampUtc()).parentTraceId(original.parentTraceId())
                .agentId(original.agentId()).sessionId(original.sessionId()).subjectId(original.subjectId())
                .modelVersion(original.modelVersion()).promptHash(original.promptHash())
                .responseHash(original.responseHash()).tokenUsage(original.tokenUsage())
                .costUsd(original.costUsd()).latencyMs(original.latencyMs())
                .piiEntityTypes(original.piiEntityTypes()).piiEntityCounts(original.piiEntityCounts())
                .interceptorsFired(original.interceptorsFired()).cacheHit(original.cacheHit())
                .previousHash(original.previousHash()).loopGuard(forged).build());
        assertFalse(AuditChain.verifyChain(tampered));
    }

    @Test
    void aDryRunActionIsRecordedOnEveryCallThatBreached() {
        CollectingSink sink = new CollectingSink();
        Scripted provider = new Scripted(0, List.of());
        Gateway gateway = gateway(provider, LoopGuard.of(
                LoopGuardPolicy.builder("agent").maxSteps(2).action(Action.DRY_RUN).build()), sink, false);

        agentLoop(gateway, 4);

        assertEquals(4, provider.seen.size());
        assertEquals(List.of("allow", "allow", "dry_run", "dry_run"),
                sink.records.stream().map(r -> r.loopGuard().get("action")).toList());
        assertEquals(java.util.Arrays.asList(null, null, "max_steps", "max_steps"),
                sink.records.stream().map(r -> r.loopGuard().get("breached")).toList());
    }

    @Test
    void aGatewayDryRunRecordsButNeverStops() {
        CollectingSink sink = new CollectingSink();
        Scripted provider = new Scripted(0, List.of());
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").dryRun(true)
                .use(new AuditInterceptor(sink, false))
                .use(LoopGuard.of(LoopGuardPolicy.builder("agent").maxSteps(1).build())).build();

        agentLoop(gateway, 3);

        assertEquals(3, provider.seen.size());
        assertEquals(List.of("allow", "dry_run", "dry_run"),
                sink.records.stream().map(r -> r.loopGuard().get("action")).toList());
    }

    @Test
    void aStoppedCallNeverReachesTheProviderOrTheAuditSink() {
        CollectingSink sink = new CollectingSink();
        Scripted provider = new Scripted(0, List.of());
        Gateway gateway = gateway(provider, LoopGuard.of(LoopGuardPolicy.builder("agent").maxSteps(2).build()),
                sink, false);

        CompletionException failure = assertThrows(CompletionException.class, () -> agentLoop(gateway, 5));

        assertTrue(failure.getCause() instanceof LoopGuardException);
        assertEquals(2, provider.seen.size());
        assertEquals(2, sink.records.size());
    }
}
