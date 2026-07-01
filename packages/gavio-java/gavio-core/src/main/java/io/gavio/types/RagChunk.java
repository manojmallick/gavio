package io.gavio.types;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single retrieved source that contributed to a prompt.
 *
 * <p>Carries a <em>reference</em> to the source — never the retrieved text — so
 * prompt lineage stays within the audit record's metadata-only contract.
 */
public record RagChunk(String source, String chunkId, Double score) {

    /** A chunk with only a source reference. */
    public static RagChunk of(String source) {
        return new RagChunk(source, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("chunk_id", chunkId);
        m.put("score", score);
        return m;
    }
}
