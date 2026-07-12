package io.gavio.prompts;

import io.gavio.types.Message;
import io.gavio.types.PromptLineage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned chat prompt template. */
public final class PromptTemplate {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");

    private final String id;
    private final String version;
    private final List<Message> messages;
    private final List<String> requiredVariables;
    private final Map<String, Object> metadata;
    private final PromptApproval approval;

    public PromptTemplate(
            String id,
            String version,
            List<Message> messages,
            List<String> requiredVariables,
            Map<String, Object> metadata) {
        this(id, version, messages, requiredVariables, metadata, null);
    }

    public PromptTemplate(
            String id,
            String version,
            List<Message> messages,
            List<String> requiredVariables,
            Map<String, Object> metadata,
            PromptApproval approval) {
        this.id = id;
        this.version = version;
        this.messages = List.copyOf(messages);
        this.requiredVariables = List.copyOf(requiredVariables == null ? List.of() : requiredVariables);
        this.metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        this.approval = approval;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public List<Message> messages() {
        return messages;
    }

    public List<String> requiredVariables() {
        return requiredVariables;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public PromptApproval approval() {
        return approval;
    }

    @SuppressWarnings("unchecked")
    public static PromptTemplate fromMap(Map<String, Object> data) {
        List<Message> messages = new ArrayList<>();
        for (Object raw : (List<Object>) data.get("messages")) {
            Map<String, Object> message = (Map<String, Object>) raw;
            messages.add(Message.of(String.valueOf(message.get("role")), String.valueOf(message.get("content"))));
        }
        List<String> required = new ArrayList<>();
        Object rawRequired = data.get("requiredVariables");
        if (rawRequired instanceof List<?> list) {
            for (Object item : list) {
                required.add(String.valueOf(item));
            }
        }
        Map<String, Object> metadata = data.get("metadata") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        PromptApproval approval = data.get("approval") instanceof Map<?, ?> a
                ? PromptApproval.fromMap((Map<String, Object>) a)
                : null;
        return new PromptTemplate(
                String.valueOf(data.get("id")),
                String.valueOf(data.get("version")),
                messages,
                required,
                metadata,
                approval);
    }

    public Set<String> placeholders() {
        Set<String> found = new LinkedHashSet<>();
        for (Message message : messages) {
            collectPlaceholders(message.role(), found);
            collectPlaceholders(message.content(), found);
        }
        return found;
    }

    public RenderedPrompt render(Map<String, Object> variables) {
        Set<String> required = new LinkedHashSet<>(requiredVariables);
        required.addAll(placeholders());
        List<String> missing = required.stream()
                .filter(key -> !variables.containsKey(key))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "prompt template " + id + "@" + version + " missing variables: " + missing);
        }

        List<Message> rendered = new ArrayList<>();
        for (Message message : messages) {
            rendered.add(Message.of(
                    renderString(message.role(), variables),
                    renderString(message.content(), variables)));
        }
        PromptLineage.Builder lineage = PromptLineage.builder()
                .templateId(id)
                .templateVersion(version);
        variables.forEach(lineage::variable);
        return new RenderedPrompt(rendered, lineage.build());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("version", version);
        List<Object> msg = new ArrayList<>();
        for (Message message : messages) {
            msg.add(Map.of("role", message.role(), "content", message.content()));
        }
        out.put("messages", msg);
        out.put("requiredVariables", requiredVariables);
        out.put("metadata", metadata);
        if (approval != null) {
            out.put("approval", approval.toMap());
        }
        return out;
    }

    public PromptDiff diff(PromptTemplate other) {
        return PromptDiff.between(this, other);
    }

    private static void collectPlaceholders(String value, Set<String> out) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    private static String renderString(String value, Map<String, Object> variables) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(variables.get(matcher.group(1)))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
