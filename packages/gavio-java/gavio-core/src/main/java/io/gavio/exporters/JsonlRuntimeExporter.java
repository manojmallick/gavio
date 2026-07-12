package io.gavio.exporters;

import io.gavio.inspector.InspectorEvent;
import io.gavio.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/** Append runtime events as one JSON object per line. */
public final class JsonlRuntimeExporter implements GavioRuntimeExporter {

    private final Path path;
    private final Consumer<String> writer;
    private final boolean metadataOnly;

    public JsonlRuntimeExporter(Path path) {
        this(path, true);
    }

    public JsonlRuntimeExporter(Path path, boolean metadataOnly) {
        this.path = path;
        this.writer = null;
        this.metadataOnly = metadataOnly;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create runtime export directory", e);
        }
    }

    public JsonlRuntimeExporter(Consumer<String> writer) {
        this(writer, true);
    }

    public JsonlRuntimeExporter(Consumer<String> writer, boolean metadataOnly) {
        this.path = null;
        this.writer = writer;
        this.metadataOnly = metadataOnly;
    }

    @Override
    public synchronized void exportEvent(InspectorEvent event) {
        String line = Json.write(metadataOnly ? RuntimeEventPrivacy.metadataOnly(event) : event.toMap()) + "\n";
        if (writer != null) {
            writer.accept(line);
            return;
        }
        try {
            Files.writeString(
                    path,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write runtime event", e);
        }
    }
}
