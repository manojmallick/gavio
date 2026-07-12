package io.gavio.prompts;

import io.gavio.json.Json;
import io.gavio.types.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Prompt-template diff that hashes message text instead of exposing it. */
public record PromptDiff(
        String fromId,
        String fromVersion,
        String toId,
        String toVersion,
        List<PromptDiffChange> changes) {

    public PromptDiff {
        changes = List.copyOf(changes);
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", Map.of("id", fromId, "version", fromVersion));
        out.put("to", Map.of("id", toId, "version", toVersion));
        out.put("hasChanges", hasChanges());
        out.put("changes", changes.stream().map(PromptDiffChange::toMap).toList());
        return out;
    }

    public static PromptDiff between(PromptTemplate before, PromptTemplate after) {
        List<PromptDiffChange> changes = new ArrayList<>();
        int maxMessages = Math.max(before.messages().size(), after.messages().size());
        for (int idx = 0; idx < maxMessages; idx++) {
            String path = "messages[" + idx + "]";
            if (idx >= before.messages().size()) {
                changes.add(new PromptDiffChange(
                        path,
                        "added",
                        null,
                        sha256Json(messageToMap(after.messages().get(idx))),
                        null,
                        null));
                continue;
            }
            if (idx >= after.messages().size()) {
                changes.add(new PromptDiffChange(
                        path,
                        "removed",
                        sha256Json(messageToMap(before.messages().get(idx))),
                        null,
                        null,
                        null));
                continue;
            }
            diffMessage(path, before.messages().get(idx), after.messages().get(idx), changes);
        }
        Set<String> beforeRequired = new LinkedHashSet<>(before.requiredVariables());
        Set<String> afterRequired = new LinkedHashSet<>(after.requiredVariables());
        if (!beforeRequired.equals(afterRequired)) {
            changes.add(new PromptDiffChange(
                    "requiredVariables",
                    "changed",
                    null,
                    null,
                    beforeRequired.stream().filter(item -> !afterRequired.contains(item)).sorted().toList(),
                    afterRequired.stream().filter(item -> !beforeRequired.contains(item)).sorted().toList()));
        }
        diffMap("metadata", before.metadata(), after.metadata(), changes);
        diffMap(
                "approval",
                before.approval() == null ? Map.of() : before.approval().toMap(),
                after.approval() == null ? Map.of() : after.approval().toMap(),
                changes);
        return new PromptDiff(before.id(), before.version(), after.id(), after.version(), changes);
    }

    private static void diffMessage(
            String path,
            Message before,
            Message after,
            List<PromptDiffChange> changes) {
        Map<String, Object> beforeMap = messageToMap(before);
        Map<String, Object> afterMap = messageToMap(after);
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(beforeMap.keySet());
        keys.addAll(afterMap.keySet());
        for (String key : keys) {
            if (java.util.Objects.equals(beforeMap.get(key), afterMap.get(key))) {
                continue;
            }
            changes.add(new PromptDiffChange(
                    path + "." + key,
                    changeType(beforeMap.containsKey(key), afterMap.containsKey(key)),
                    beforeMap.containsKey(key) ? sha256Json(beforeMap.get(key)) : null,
                    afterMap.containsKey(key) ? sha256Json(afterMap.get(key)) : null,
                    null,
                    null));
        }
    }

    private static void diffMap(
            String prefix,
            Map<String, Object> before,
            Map<String, Object> after,
            List<PromptDiffChange> changes) {
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (java.util.Objects.equals(before.get(key), after.get(key))) {
                continue;
            }
            changes.add(new PromptDiffChange(
                    prefix + "." + key,
                    changeType(before.containsKey(key), after.containsKey(key)),
                    null,
                    null,
                    before.get(key),
                    after.get(key)));
        }
    }

    private static String changeType(boolean hasBefore, boolean hasAfter) {
        if (hasBefore && hasAfter) {
            return "changed";
        }
        return hasAfter ? "added" : "removed";
    }

    private static Map<String, Object> messageToMap(Message message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", message.role());
        out.put("content", message.content());
        return out;
    }

    static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PromptDiff::canonicalize).toList();
        }
        return value;
    }

    private static String sha256Json(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Json.write(canonicalize(value)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
