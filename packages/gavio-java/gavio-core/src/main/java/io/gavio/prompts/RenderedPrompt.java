package io.gavio.prompts;

import io.gavio.GavioRequest;
import io.gavio.types.Message;
import io.gavio.types.PromptLineage;
import io.gavio.types.Provider;
import java.util.List;
import java.util.Map;

/** Rendered prompt messages plus metadata-only PromptLineage. */
public record RenderedPrompt(List<Message> messages, PromptLineage lineage) {

    public RenderedPrompt {
        messages = List.copyOf(messages);
    }

    public GavioRequest toRequest(String model) {
        return toRequest(model, Provider.MOCK, Map.of(), Map.of());
    }

    public GavioRequest toRequest(
            String model,
            Provider provider,
            Map<String, Object> metadata,
            Map<String, Object> options) {
        GavioRequest.Builder builder = GavioRequest.builder()
                .messages(messages)
                .model(model)
                .provider(provider)
                .lineage(lineage);
        if (metadata != null) {
            metadata.forEach(builder::metadata);
        }
        if (options != null) {
            options.forEach(builder::option);
        }
        return builder.build();
    }
}
