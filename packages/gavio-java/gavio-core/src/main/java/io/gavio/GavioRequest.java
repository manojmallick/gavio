package io.gavio;

import io.gavio.json.CanonicalJson;
import io.gavio.json.JsonValues;
import io.gavio.types.Message;
import io.gavio.types.PromptLineage;
import io.gavio.types.Provider;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
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
 *
 * <p>{@code tools} declares the tools the model may call ({@code ToolDefinition} objects, as
 * {@code spec/ToolDefinition.schema.json}; only name, description and input schema reach the
 * provider) and {@code toolChoice} steers it (F-ADP-03). Both are empty/null for a plain request.
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
        List<byte[]> images,
        PromptLineage lineage,
        List<Map<String, Object>> tools,
        ToolChoice toolChoice) {

    public GavioRequest {
        messages = List.copyOf(messages);
        options = Map.copyOf(options == null ? Map.of() : options);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        images = images == null ? List.of() : List.copyOf(images);
        tools = toolList(tools);
        if (traceId == null) {
            traceId = Ids.newTraceId();
        }
    }

    /** A request without tool declarations — the shape before F-ADP-03. */
    public GavioRequest(
            List<Message> messages,
            String model,
            Provider provider,
            String traceId,
            String agentId,
            String parentTraceId,
            String sessionId,
            Map<String, Object> options,
            Map<String, Object> metadata,
            List<byte[]> images,
            PromptLineage lineage) {
        this(messages, model, provider, traceId, agentId, parentTraceId, sessionId,
                options, metadata, images, lineage, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolList(List<Map<String, Object>> tools) {
        return tools == null ? List.of() : (List<Map<String, Object>>) JsonValues.immutableCopy(tools);
    }

    /** True when the request declares tools for the model to call. */
    public boolean hasTools() {
        return !tools.isEmpty();
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

    /**
     * Concatenate message contents — used for hashing and token estimation.
     *
     * <p>Tool calls an assistant message made are included as {@code name(canonical-args)} so the
     * hash and the estimate see them; a message without tool calls contributes exactly its content,
     * so prompt hashes of existing requests do not change.
     */
    public String promptText() {
        List<String> parts = new ArrayList<>();
        for (Message message : messages) {
            parts.add(message.content());
            for (ToolCall call : message.toolCalls()) {
                parts.add(call.name() + "(" + CanonicalJson.write(call.arguments()) + ")");
            }
        }
        return String.join("\n", parts);
    }

    /** Return a shallow copy with replaced messages (interceptors mutate via this). */
    public GavioRequest withMessages(List<Message> newMessages) {
        return new GavioRequest(
                newMessages, model, provider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, images, lineage, tools, toolChoice);
    }

    /** Return a copy with a different provider (used by fallback rerouting). */
    public GavioRequest withProvider(Provider newProvider) {
        return new GavioRequest(
                messages, model, newProvider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, images, lineage, tools, toolChoice);
    }

    /** Return a copy with a different model (used by cost-optimiser rerouting). */
    public GavioRequest withModel(String newModel) {
        return new GavioRequest(
                messages, newModel, provider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, images, lineage, tools, toolChoice);
    }

    /** Return a copy with different tool declarations and choice. */
    public GavioRequest withTools(List<Map<String, Object>> newTools, ToolChoice newToolChoice) {
        return new GavioRequest(
                messages, model, provider, traceId, agentId,
                parentTraceId, sessionId, options, metadata, images, lineage,
                newTools, newToolChoice);
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
        private final List<byte[]> images = new ArrayList<>();
        private PromptLineage lineage;
        private final List<Map<String, Object>> tools = new ArrayList<>();
        private ToolChoice toolChoice;

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

        public Builder images(List<byte[]> images) {
            this.images.clear();
            this.images.addAll(images);
            return this;
        }

        public Builder image(byte[] image) {
            this.images.add(image);
            return this;
        }

        public Builder lineage(PromptLineage lineage) {
            this.lineage = lineage;
            return this;
        }

        /** Declare the tools the model may call (F-ADP-03). */
        public Builder tools(List<Map<String, Object>> tools) {
            this.tools.clear();
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        /** Declare one more tool the model may call. */
        public Builder tool(Map<String, Object> tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * Steer tool use with a wire-shaped choice: {@code "auto"}, {@code "none"},
         * {@code "required"} or a map with a {@code name}.
         *
         * @throws io.gavio.GavioException.ConfigurationException for any other value
         */
        public Builder toolChoice(Object toolChoice) {
            this.toolChoice = ToolChoice.parse(toolChoice);
            return this;
        }

        public GavioRequest build() {
            return new GavioRequest(
                    new ArrayList<>(messages), model, provider, traceId,
                    agentId, parentTraceId, sessionId,
                    new HashMap<>(options), new HashMap<>(metadata),
                    new ArrayList<>(images), lineage, new ArrayList<>(tools), toolChoice);
        }
    }
}
