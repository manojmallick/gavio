package io.gavio;

import io.gavio.types.Message;
import io.gavio.types.PromptLineage;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical, provider-agnostic request model.
 *
 * <p>Immutable record. A {@code traceId} (UUID v7, time-sortable) is assigned
 * automatically if not supplied. {@code parentTraceId} links calls into a
 * multi-agent DAG.
 */
public record GavioRequest(
        List<Message> messages,
        String model,
        Provider provider,
        String traceId,
        String agentId,
        String parentTraceId,
        String sessionId,
        Map<String, Object> options,
        Map<String, Object> metadata,
        PromptLineage lineage) {

    public GavioRequest {
        messages = List.copyOf(messages);
        options = Map.copyOf(options == null ? Map.of() : options);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if (traceId == null) {
            traceId = Ids.newTraceId();
        }
    }

    public double temperature() {
        Object t = options.get("temperature");
        return t == null ? 0.7 : ((Number) t).doubleValue();
    }

    public int maxTokens() {
        Object t = options.get("maxTokens");
        if (t == null) {
            t = options.get("max_tokens");
        }
        return t == null ? 1024 : ((Number) t).intValue();
    }

    /** Concatenate message contents — used for hashing and token estimation. */
    public String promptText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(messages.get(i).content());
        }
        return sb.toString();
    }

    /** Return a shallow copy with replaced messages (interceptors mutate via this). */
    public GavioRequest withMessages(List<Message> newMessages) {
        return new GavioRequest(
                newMessages, model, provider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, lineage);
    }

    /** Return a copy with a different provider (used by fallback rerouting). */
    public GavioRequest withProvider(Provider newProvider) {
        return new GavioRequest(
                messages, model, newProvider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, lineage);
    }

    /** Return a copy with a different model (used by cost-optimiser rerouting). */
    public GavioRequest withModel(String newModel) {
        return new GavioRequest(
                messages, newModel, provider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, lineage);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link GavioRequest}. */
    public static final class Builder {
        private final List<Message> messages = new ArrayList<>();
        private String model = "mock";
        private Provider provider = Provider.MOCK;
        private String traceId;
        private String agentId;
        private String parentTraceId;
        private String sessionId;
        private final Map<String, Object> options = new LinkedHashMap<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private PromptLineage lineage;

        public Builder messages(List<Message> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder message(String role, String content) {
            this.messages.add(Message.of(role, content));
            return this;
        }

        public Builder message(Message message) {
            this.messages.add(message);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder provider(Provider provider) {
            this.provider = provider;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder parentTraceId(String parentTraceId) {
            this.parentTraceId = parentTraceId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(key, value);
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder lineage(PromptLineage lineage) {
            this.lineage = lineage;
            return this;
        }

        public GavioRequest build() {
            return new GavioRequest(
                    new ArrayList<>(messages), model, provider, traceId,
                    agentId, parentTraceId, sessionId,
                    new HashMap<>(options), new HashMap<>(metadata), lineage);
        }
    }
}
