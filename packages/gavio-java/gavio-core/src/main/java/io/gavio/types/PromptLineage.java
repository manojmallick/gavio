package io.gavio.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provenance for a rendered prompt (F-OBS-04): the template, the variable
 * bindings interpolated into it, and the RAG chunk sources retrieved for it.
 *
 * <p>Attached to a {@link io.gavio.GavioRequest} by the caller and copied into
 * the audit record so any prompt can be reconstructed and debugged. RAG chunk
 * text is never stored — only source references (see {@link RagChunk}).
 */
public record PromptLineage(
        String templateId,
        String templateVersion,
        Map<String, Object> variables,
        List<RagChunk> ragChunks) {

    public PromptLineage {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        ragChunks = ragChunks == null ? List.of() : List.copyOf(ragChunks);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", templateId);
        m.put("template_version", templateVersion);
        m.put("variables", new LinkedHashMap<>(variables));
        List<Object> chunks = new ArrayList<>();
        for (RagChunk chunk : ragChunks) {
            chunks.add(chunk.toMap());
        }
        m.put("rag_chunks", chunks);
        return m;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link PromptLineage}. */
    public static final class Builder {
        private String templateId;
        private String templateVersion;
        private final Map<String, Object> variables = new LinkedHashMap<>();
        private final List<RagChunk> ragChunks = new ArrayList<>();

        public Builder templateId(String v) {
            this.templateId = v;
            return this;
        }

        public Builder templateVersion(String v) {
            this.templateVersion = v;
            return this;
        }

        public Builder variable(String key, Object value) {
            this.variables.put(key, value);
            return this;
        }

        public Builder ragChunk(RagChunk chunk) {
            this.ragChunks.add(chunk);
            return this;
        }

        public PromptLineage build() {
            return new PromptLineage(
                    templateId,
                    templateVersion,
                    new LinkedHashMap<>(variables),
                    new ArrayList<>(ragChunks));
        }
    }
}
