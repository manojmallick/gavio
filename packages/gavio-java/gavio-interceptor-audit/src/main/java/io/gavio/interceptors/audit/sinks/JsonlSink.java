package io.gavio.interceptors.audit.sinks;

import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.audit.AuditSink;
import io.gavio.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Append each audit record as one JSON line (F-DX-08, F-QUA-09). Zero
 * dependencies.
 *
 * <p>Supports {@link #purge(String)} for right-to-erasure: matching lines are
 * dropped and the file is rewritten atomically (temp file + move).
 */
public final class JsonlSink implements AuditSink {

    private final Path path;
    private final Object lock = new Object();

    public JsonlSink(Path path) {
        this.path = path;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonlSink(String path) {
        this(Path.of(path));
    }

    @Override
    public CompletableFuture<Void> write(AuditRecord record) {
        synchronized (lock) {
            try {
                Files.writeString(
                        path,
                        record.toJson() + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Integer> purge(String subjectId) {
        synchronized (lock) {
            if (!Files.exists(path)) {
                return CompletableFuture.completedFuture(0);
            }
            try {
                List<String> kept = new ArrayList<>();
                int removed = 0;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (matchesSubject(line, subjectId)) {
                        removed++;
                        continue;
                    }
                    kept.add(line);
                }
                if (removed > 0) {
                    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                    StringBuilder sb = new StringBuilder();
                    for (String line : kept) {
                        sb.append(line).append('\n');
                    }
                    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return CompletableFuture.completedFuture(removed);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static boolean matchesSubject(String line, String subjectId) {
        try {
            Object value = Json.parseObject(line).get("subject_id");
            return subjectId.equals(value);
        } catch (RuntimeException e) {
            return false; // preserve non-JSON lines untouched
        }
    }
}
