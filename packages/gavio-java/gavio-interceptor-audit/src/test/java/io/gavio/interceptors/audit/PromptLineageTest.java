package io.gavio.interceptors.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.types.PromptLineage;
import io.gavio.types.RagChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Prompt lineage (F-OBS-04). */
class PromptLineageTest {

    private static final class Collector implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.add(record);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static PromptLineage lineage() {
        return PromptLineage.builder()
                .templateId("support-reply")
                .templateVersion("v3")
                .variable("customer", "Ada")
                .ragChunk(new RagChunk("doc://kb/refunds", "c1", 0.92))
                .ragChunk(RagChunk.of("doc://kb/shipping"))
                .build();
    }

    @Test
    void ragChunkCarriesSourceReferenceOnly() {
        Map<String, Object> m = new RagChunk("doc://kb/refunds", "c1", 0.92).toMap();
        assertEquals("doc://kb/refunds", m.get("source"));
        assertEquals("c1", m.get("chunk_id"));
        assertEquals(0.92, m.get("score"));
        assertFalse(m.containsKey("text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lineageSerialisesToNestedMap() {
        Map<String, Object> m = lineage().toMap();
        assertEquals("support-reply", m.get("template_id"));
        assertEquals("v3", m.get("template_version"));
        assertEquals(Map.of("customer", "Ada"), m.get("variables"));
        List<Object> chunks = (List<Object>) m.get("rag_chunks");
        assertEquals(2, chunks.size());
        assertEquals("doc://kb/refunds", ((Map<String, Object>) chunks.get(0)).get("source"));
    }

    @Test
    void lineageSurvivesWithMessages() {
        PromptLineage lin = lineage();
        GavioRequest req = GavioRequest.builder().message("user", "hi").model("mock").lineage(lin).build();
        assertEquals(lin, req.withMessages(List.of()).lineage());
    }

    @Test
    void lineageFlowsIntoAuditRecord() {
        Collector sink = new Collector();
        Gateway gw = Gateway.builder().devMode(true).use(AuditInterceptor.builder().sink(sink).build()).build();
        gw.complete(GavioRequest.builder().message("user", "hi").model("mock").lineage(lineage()).build()).join();

        assertEquals(1, sink.records.size());
        AuditRecord rec = sink.records.get(0);
        assertEquals("support-reply", rec.lineage().templateId());
        assertTrue(rec.toJson().contains("doc://kb/refunds"));
    }

    @Test
    void lineageIsNullWhenAbsent() {
        Collector sink = new Collector();
        Gateway gw = Gateway.builder().devMode(true).use(AuditInterceptor.builder().sink(sink).build()).build();
        gw.complete(GavioRequest.builder().message("user", "hi").model("mock").build()).join();

        AuditRecord rec = sink.records.get(0);
        assertNull(rec.lineage());
        assertTrue(rec.toMap().containsKey("lineage"));
        assertNull(rec.toMap().get("lineage"));
    }

    @Test
    void lineageParticipatesInContentHash() {
        AuditRecord.Builder base =
                AuditRecord.builder().traceId("t1").provider("mock").model("mock")
                        .timestampUtc("2026-07-01T00:00:00Z");
        String without = base.build().contentHash();
        String with = AuditRecord.builder().traceId("t1").provider("mock").model("mock")
                .timestampUtc("2026-07-01T00:00:00Z").lineage(lineage()).build().contentHash();
        String other = AuditRecord.builder().traceId("t1").provider("mock").model("mock")
                .timestampUtc("2026-07-01T00:00:00Z")
                .lineage(PromptLineage.builder().templateId("other").build()).build().contentHash();

        assertNotEquals(without, with);
        assertNotEquals(with, other);
    }
}
