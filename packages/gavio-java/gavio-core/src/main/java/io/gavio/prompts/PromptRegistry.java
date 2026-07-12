package io.gavio.prompts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory registry for versioned prompt templates. */
public final class PromptRegistry {
    private final Map<String, PromptTemplate> templates = new HashMap<>();
    private final Map<String, String> latest = new HashMap<>();

    public PromptRegistry() {
    }

    public PromptRegistry(List<PromptTemplate> templates) {
        for (PromptTemplate template : templates) {
            register(template);
        }
    }

    public PromptTemplate register(PromptTemplate template) {
        templates.put(key(template.id(), template.version()), template);
        latest.put(template.id(), template.version());
        return template;
    }

    public PromptTemplate get(String templateId) {
        return get(templateId, null);
    }

    public PromptTemplate get(String templateId, String version) {
        String resolved = version != null ? version : latest.get(templateId);
        PromptTemplate template = resolved == null ? null : templates.get(key(templateId, resolved));
        if (template == null) {
            throw new IllegalArgumentException(
                    "prompt template not found: " + templateId + "@" + (version == null ? "latest" : version));
        }
        return template;
    }

    public RenderedPrompt render(String templateId, Map<String, Object> variables) {
        return render(templateId, variables, null);
    }

    public RenderedPrompt render(String templateId, Map<String, Object> variables, String version) {
        return get(templateId, version).render(variables);
    }

    private static String key(String id, String version) {
        return id + "@" + version;
    }
}
