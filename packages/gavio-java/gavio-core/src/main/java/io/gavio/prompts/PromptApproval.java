package io.gavio.prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Human approval metadata for a prompt template version. */
public record PromptApproval(
        String status,
        String approvedBy,
        String approvedAt,
        List<String> reviewers,
        String reason,
        Map<String, Object> metadata) {

    public PromptApproval {
        reviewers = List.copyOf(reviewers == null ? List.of() : reviewers);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    @SuppressWarnings("unchecked")
    public static PromptApproval fromMap(Map<String, Object> data) {
        List<String> reviewers = new ArrayList<>();
        Object rawReviewers = data.get("reviewers");
        if (rawReviewers instanceof List<?> list) {
            for (Object item : list) {
                reviewers.add(String.valueOf(item));
            }
        }
        Map<String, Object> metadata = data.get("metadata") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return new PromptApproval(
                String.valueOf(data.get("status")),
                optionalString(data.get("approvedBy")),
                optionalString(data.get("approvedAt")),
                reviewers,
                optionalString(data.get("reason")),
                metadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("reviewers", reviewers);
        if (approvedBy != null) {
            out.put("approvedBy", approvedBy);
        }
        if (approvedAt != null) {
            out.put("approvedAt", approvedAt);
        }
        if (reason != null) {
            out.put("reason", reason);
        }
        if (!metadata.isEmpty()) {
            out.put("metadata", metadata);
        }
        return out;
    }

    private static String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
