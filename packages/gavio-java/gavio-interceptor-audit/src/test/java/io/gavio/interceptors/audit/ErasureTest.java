package io.gavio.interceptors.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.interceptors.audit.sinks.JsonlSink;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErasureTest {

    private static final class CollectingSink implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.add(record);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static AuditRecord rec(String subjectId, String trace) {
        return AuditRecord.builder()
                .traceId(trace)
                .provider("mock")
                .model("m")
                .subjectId(subjectId)
                .build();
    }

    private static Gateway gw(AuditSink sink) {
        return Gateway.builder()
                .adapter(new MockProvider("hi"))
                .model("mock")
                .use(new AuditInterceptor(sink))
                .build();
    }

    @Test
    void subjectIdPersistedFromMetadata() {
        CollectingSink sink = new CollectingSink();
        GavioRequest req = GavioRequest.builder()
                .message("user", "q")
                .model("mock")
                .metadata("subject_id", "user-123")
                .build();
        gw(sink).complete(req).join();
        assertEquals("user-123", sink.records.get(0).subjectId());
    }

    @Test
    void subjectIdNullWhenAbsent() {
        CollectingSink sink = new CollectingSink();
        gw(sink).complete(GavioRequest.builder().message("user", "q").model("mock").build()).join();
        assertNull(sink.records.get(0).subjectId());
    }

    @Test
    void jsonlPurgeRemovesMatchingAndReturnsCount(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("audit.jsonl");
        JsonlSink sink = new JsonlSink(path);
        sink.write(rec("u1", "t1")).join();
        sink.write(rec("u2", "t2")).join();
        sink.write(rec("u1", "t3")).join();

        int removed = sink.purge("u1").join();

        assertEquals(2, removed);
        List<String> lines =
                Files.readAllLines(path).stream().filter(l -> !l.isBlank()).toList();
        assertEquals(1, lines.size());
        assertEquals("u2", Json.parseObject(lines.get(0)).get("subject_id"));
    }

    @Test
    void purgeZeroOnMissingFileAndNoMatch(@TempDir Path dir) throws Exception {
        JsonlSink sink = new JsonlSink(dir.resolve("audit.jsonl"));
        assertEquals(0, sink.purge("nobody").join()); // file not yet written
        sink.write(rec("u1", "t1")).join();
        assertEquals(0, sink.purge("nobody").join());
    }
}
