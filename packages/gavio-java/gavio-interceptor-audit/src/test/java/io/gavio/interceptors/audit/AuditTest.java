package io.gavio.interceptors.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.interceptors.audit.sinks.StdoutSink;
import io.gavio.types.TokenUsage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void recordSerialisesWithoutContent() {
        AuditRecord rec = AuditRecord.builder()
                .traceId("t-1").provider("mock").model("mock")
                .promptHash(AuditRecord.hashText("hello"))
                .tokenUsage(new TokenUsage(10, 5))
                .build();
        assertEquals(15, rec.tokenUsage().totalTokens());
        assertEquals("1.0", rec.schemaVersion());
        assertFalse(rec.toJson().contains("hello")); // only the hash, never the text
    }

    @Test
    void hashesAre64HexChars() {
        String h = AuditRecord.hashText("anything");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    void stdoutSinkWritesLine() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StdoutSink sink = new StdoutSink(true, new PrintStream(buf, true, StandardCharsets.UTF_8));
        AuditRecord rec = AuditRecord.builder()
                .traceId("trace-abc").provider("mock").model("mock").build();
        sink.write(rec).join();
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("gavio:audit"));
        assertTrue(out.contains("mock/mock"));
    }

    @Test
    void contentHashIsStable() {
        AuditRecord rec = AuditRecord.builder()
                .traceId("t").provider("mock").model("mock").timestampUtc("2026-07-01T00:00:00Z")
                .build();
        assertEquals(rec.contentHash(), rec.contentHash());
        assertEquals(64, rec.contentHash().length());
    }
}
