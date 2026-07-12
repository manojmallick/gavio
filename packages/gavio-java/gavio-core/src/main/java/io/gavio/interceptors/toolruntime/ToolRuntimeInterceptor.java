package io.gavio.interceptors.toolruntime;

import io.gavio.GavioException.ToolRuntimeException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.json.Json;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Tool Runtime (F-TOOL-01/02/03/04).
 *
 * <p>Consumes {@code metadata.tools.calls[]} / {@code toolContext.calls[]} and
 * records schema, freshness, conflict, confidence, and provenance decisions in
 * {@code ctx.tools().get("runtime")} plus Inspector decision state.
 */
public final class ToolRuntimeInterceptor implements Interceptor {

    public enum OnFailure {
        WARN,
        ERROR
    }

    private final OnFailure onFailure;
    private final Double maxAgeSeconds;
    private final List<String> conflictKeys;

    private ToolRuntimeInterceptor(Builder b) {
        this.onFailure = b.onFailure;
        this.maxAgeSeconds = b.maxAgeSeconds;
        this.conflictKeys = List.copyOf(b.conflictKeys);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Map<String, Object> analyze(Map<String, Object> tools) {
        return analyze(tools, null, List.of(), null);
    }

    public static Map<String, Object> replay(Map<String, Object> record) {
        return analyze(record, null, List.of(), null);
    }

    public static Map<String, Object> analyze(
            Map<String, Object> tools,
            Double maxAgeSeconds,
            Collection<String> conflictKeys,
            Instant now) {
        Map<String, Object> context = tools == null ? Map.of() : new LinkedHashMap<>(tools);
        List<Map<String, Object>> calls = calls(context);
        Map<String, Map<String, Object>> definitions = definitions(context);
        Instant referenceTime = now;
        if (referenceTime == null) {
            referenceTime = parseTime(first(context, "now", "evaluated_at"));
        }
        if (referenceTime == null) {
            referenceTime = Instant.now();
        }
        Double defaultMaxAge = maxAgeSeconds;
        if (defaultMaxAge == null) {
            defaultMaxAge = number(first(context, "max_age_seconds", "maxAgeSeconds"));
        }
        List<String> grantedPermissions = grantedPermissions(context);
        List<Map<String, Object>> approvals = approvals(context);

        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        List<Double> confidenceValues = new ArrayList<>();
        List<Map<String, Object>> decisions = new ArrayList<>();

        for (Map<String, Object> call : calls) {
            String toolId = toolId(call);
            String toolName = toolName(call);
            Map<String, Object> definition = definitionFor(call, definitions);
            Map<String, Object> result = record(first(call, "result", "output"));
            Map<String, Object> input = record(first(call, "input", "arguments", "args"));
            List<String> requiredPermissions = requiredPermissions(call, definition);
            List<String> missingPermissions = requiredPermissions.stream()
                    .filter(permission -> !permissionGranted(permission, grantedPermissions))
                    .toList();
            String risk = riskLevel(call, definition);
            boolean approvalRequired = approvalRequired(call, definition, risk, requiredPermissions);
            Map<String, Object> approval = matchingApproval(call, approvals, referenceTime);
            boolean approved = approval != null;

            Map<String, Object> inputSchema = record(first(call, "input_schema", "inputSchema"));
            if (inputSchema.isEmpty()) {
                inputSchema = record(first(definition, "input_schema", "inputSchema"));
            }
            if (!inputSchema.isEmpty()) {
                violations.addAll(validateSchema(input, inputSchema, "input", toolId, toolName));
            }
            Map<String, Object> outputSchema = record(first(call, "output_schema", "outputSchema", "schema"));
            if (outputSchema.isEmpty()) {
                outputSchema = record(first(definition, "output_schema", "outputSchema", "schema"));
            }
            if (!outputSchema.isEmpty()) {
                violations.addAll(validateSchema(result, outputSchema, "output", toolId, toolName));
            }

            Instant createdAt = parseTime(first(call, "created_at", "createdAt", "timestamp", "observed_at"));
            Double ttl = number(first(call, "ttl_seconds", "ttlSeconds", "max_age_seconds", "maxAgeSeconds"));
            if (ttl == null) {
                ttl = number(first(definition, "freshness_ttl_seconds", "freshnessTtlSeconds"));
            }
            if (ttl == null) {
                ttl = defaultMaxAge;
            }
            if (createdAt != null && ttl != null) {
                double age = Math.max(0.0, (referenceTime.toEpochMilli() - createdAt.toEpochMilli()) / 1000.0);
                if (age > ttl) {
                    violations.add(violation(
                            "freshness",
                            toolId,
                            toolName,
                            String.format("tool result is stale: age %.1fs exceeds %.1fs", age, ttl),
                            Map.of("age_seconds", round(age), "max_age_seconds", ttl)));
                }
            }

            Map<String, Object> mcp = mcpMetadata(call, definition);
            Object source = first(call, "source", "provider", "provenance");
            if (source == null && !mcp.isEmpty()) {
                source = mcp.getOrDefault("server", mcp.getOrDefault("tool", "mcp"));
            }
            if (bool(first(call, "provenance_required", "provenanceRequired"))
                    || bool(first(definition, "provenance_required", "provenanceRequired"))) {
                if (source == null && mcp.isEmpty()) {
                    violations.add(violation(
                            "provenance",
                            toolId,
                            toolName,
                            "tool result is missing required provenance",
                            Map.of()));
                }
            }

            String action = "allow";
            if (!missingPermissions.isEmpty()) {
                action = approvalRequired && !approved ? "require_approval" : "block";
                if (!approved) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("action", action);
                    extra.put("missing_permissions", missingPermissions);
                    violations.add(violation(
                            "permission",
                            toolId,
                            toolName,
                            "tool call is missing required permission(s): " + String.join(", ", missingPermissions),
                            extra));
                }
            }
            if (approvalRequired && !approved) {
                action = "require_approval";
                violations.add(violation(
                        "approval",
                        toolId,
                        toolName,
                        "tool call requires approval",
                        Map.of("action", action, "risk", risk)));
            }

            Double confidence = number(call.get("confidence"));
            if (confidence != null) {
                confidenceValues.add(confidence);
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("tool_id", toolId);
            trace.put("tool_name", toolName);
            trace.put("source", source == null ? "unknown" : String.valueOf(source));
            trace.put("created_at", createdAt == null ? null : createdAt.toString());
            trace.put("cache_hit", Boolean.TRUE.equals(first(call, "cache_hit", "cacheHit")));
            trace.put("confidence", confidence);
            trace.put("mcp", mcp.isEmpty() ? null : mcp);
            trace.put("mcp_server", mcp.get("server"));
            trace.put("mcp_tool", mcp.get("tool"));
            trace.put("result_keys", result.keySet().stream().sorted().toList());
            provenance.add(trace);

            Map<String, Object> callDecision = new LinkedHashMap<>();
            callDecision.put("tool_id", toolId);
            callDecision.put("tool_name", toolName);
            callDecision.put("action", action);
            callDecision.put("risk", risk);
            callDecision.put("permissions_required", requiredPermissions);
            callDecision.put("permissions_granted", grantedPermissions);
            callDecision.put("missing_permissions", missingPermissions);
            callDecision.put("approval_required", approvalRequired);
            callDecision.put("approved", approved);
            callDecision.put("mcp", mcp.isEmpty() ? null : mcp);
            decisions.add(callDecision);
        }

        List<Map<String, Object>> conflicts = conflicts(calls, context, conflictKeys);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("call_count", calls.size());
        decision.put("violations", violations);
        decision.put("conflicts", conflicts);
        decision.put("confidence", overallConfidence(conflicts, confidenceValues));
        decision.put("provenance", provenance);
        decision.put("decisions", decisions);
        decision.put("approvals_required", decisions.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("approval_required")))
                .count());
        decision.put("blocked", decisions.stream()
                .filter(item -> "block".equals(item.get("action")))
                .count());
        decision.put("replayable", !calls.isEmpty());
        return decision;
    }

    @Override
    public String name() {
        return "tool_runtime";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        ctx.markFired(name());
        Map<String, Object> decision = analyze(ctx.tools(), maxAgeSeconds, conflictKeys, null);
        ctx.tools().put("runtime", decision);
        ctx.inspect("tool_runtime", decision);
        for (Map<String, Object> conflict : (List<Map<String, Object>>) decision.get("conflicts")) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("kind", "tool_conflict");
            event.putAll(conflict);
            ctx.recordGovernanceEvent(event);
        }
        for (Map<String, Object> callDecision : (List<Map<String, Object>>) decision.get("decisions")) {
            if (!"allow".equals(callDecision.get("action"))) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("kind", "tool_runtime_decision");
                event.putAll(callDecision);
                ctx.recordGovernanceEvent(event);
            }
        }
        List<Map<String, Object>> violations = (List<Map<String, Object>>) decision.get("violations");
        if (!violations.isEmpty() && onFailure == OnFailure.ERROR && !ctx.dryRun()) {
            return CompletableFuture.failedFuture(new ToolRuntimeException(messages(violations)));
        }
        return CompletableFuture.completedFuture(request);
    }

    private static String messages(List<Map<String, Object>> violations) {
        return String.join("; ", violations.stream()
                .map(v -> String.valueOf(v.get("message")))
                .toList());
    }

    private static List<Map<String, Object>> calls(Map<String, Object> tools) {
        Object raw = first(tools, "calls", "tool_calls", "toolCalls", "results", "records");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(copyMap(map));
            }
        }
        return out;
    }

    private static Map<String, Map<String, Object>> definitions(Map<String, Object> tools) {
        Object raw = first(tools, "definitions", "tool_definitions", "toolDefinitions", "registry");
        if (raw instanceof Map<?, ?> map) {
            Object nested = first(copyMap(map), "tools", "definitions");
            raw = nested == null ? raw : nested;
        }
        List<Map<String, Object>> definitions = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    definitions.add(copyMap(map));
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    Map<String, Object> definition = copyMap(value);
                    definition.putIfAbsent("name", String.valueOf(entry.getKey()));
                    definitions.add(definition);
                }
            }
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> definition : definitions) {
            Object id = first(definition, "id", "tool_id", "toolId");
            if (id != null) {
                out.put(String.valueOf(id), definition);
            }
            Object name = first(definition, "name", "tool", "tool_name", "toolName");
            if (name != null) {
                out.put(String.valueOf(name), definition);
            }
        }
        return out;
    }

    private static Map<String, Object> definitionFor(
            Map<String, Object> call,
            Map<String, Map<String, Object>> definitions) {
        Map<String, Object> inline = record(first(call, "definition"));
        if (!inline.isEmpty()) {
            return inline;
        }
        Object[] keys = new Object[] {
                first(call, "definition_id", "definitionId"),
                first(call, "name", "tool", "tool_name", "toolName"),
                first(call, "id", "tool_call_id", "toolCallId")
        };
        for (Object key : keys) {
            if (key != null && definitions.containsKey(String.valueOf(key))) {
                return definitions.get(String.valueOf(key));
            }
        }
        return Map.of();
    }

    private static List<String> requiredPermissions(Map<String, Object> call, Map<String, Object> definition) {
        Set<String> permissions = new LinkedHashSet<>();
        for (Map<String, Object> source : List.of(definition, call)) {
            for (String key : List.of("permissions", "required_permissions", "requiredPermissions")) {
                Object raw = source.get(key);
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null) {
                            permissions.add(String.valueOf(item));
                        }
                    }
                }
            }
        }
        return permissions.stream().sorted().toList();
    }

    private static List<String> grantedPermissions(Map<String, Object> tools) {
        Set<String> permissions = new LinkedHashSet<>();
        for (String key : List.of(
                "permissions",
                "granted_permissions",
                "grantedPermissions",
                "allowed_permissions",
                "allowedPermissions",
                "scopes")) {
            Object raw = tools.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        permissions.add(String.valueOf(item));
                    }
                }
            }
        }
        return permissions.stream().sorted().toList();
    }

    private static boolean permissionGranted(String required, List<String> grants) {
        for (String grant : grants) {
            if ("*".equals(grant) || required.equals(grant)) {
                return true;
            }
            if (grant.endsWith(".*") && required.startsWith(grant.substring(0, grant.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String riskLevel(Map<String, Object> call, Map<String, Object> definition) {
        Object value = first(call, "risk", "risk_level", "riskLevel");
        if (value == null) {
            value = first(definition, "risk", "risk_level", "riskLevel");
        }
        return value == null ? "low" : String.valueOf(value).toLowerCase();
    }

    private static boolean approvalRequired(
            Map<String, Object> call,
            Map<String, Object> definition,
            String risk,
            List<String> permissions) {
        Object explicit = first(call, "requires_approval", "requiresApproval");
        if (explicit == null) {
            explicit = first(definition, "requires_approval", "requiresApproval");
        }
        if (explicit != null) {
            return bool(explicit);
        }
        if (Set.of("high", "critical", "destructive").contains(risk)) {
            return true;
        }
        for (String permission : permissions) {
            if (permission.startsWith("destructive.") || permission.startsWith("external_side_effect.")) {
                return true;
            }
            if (Set.of("destructive", "destructive.*", "external_side_effect", "external_side_effect.*")
                    .contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> approvals(Map<String, Object> tools) {
        Object raw = first(tools, "approvals", "approval_records", "approvalRecords");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(copyMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> matchingApproval(
            Map<String, Object> call,
            List<Map<String, Object>> approvals,
            Instant now) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Object> inline = record(first(call, "approval"));
        if (!inline.isEmpty()) {
            candidates.add(inline);
        }
        candidates.addAll(approvals);
        Set<String> ids = Set.of(toolId(call), toolName(call));
        for (Map<String, Object> approval : candidates) {
            Object target = first(
                    approval,
                    "tool_call_id",
                    "toolCallId",
                    "call_id",
                    "callId",
                    "tool_id",
                    "toolId");
            if (target != null && !ids.contains(String.valueOf(target))) {
                continue;
            }
            if (target == null) {
                Object name = first(approval, "tool_name", "toolName", "name", "tool");
                if (name != null && !ids.contains(String.valueOf(name))) {
                    continue;
                }
            }
            String status = String.valueOf(first(approval, "status", "decision")).toLowerCase();
            boolean approved = bool(first(approval, "approved"))
                    || Set.of("approved", "allow", "allowed").contains(status);
            if (!approved || bool(first(approval, "revoked", "denied"))) {
                continue;
            }
            Instant expiresAt = parseTime(first(approval, "expires_at", "expiresAt"));
            if (expiresAt != null && expiresAt.isBefore(now)) {
                continue;
            }
            return approval;
        }
        return null;
    }

    private static Map<String, Object> mcpMetadata(Map<String, Object> call, Map<String, Object> definition) {
        Map<String, Object> out = new LinkedHashMap<>(record(first(definition, "mcp", "mcp_metadata", "mcpMetadata")));
        out.putAll(record(first(call, "mcp", "mcp_metadata", "mcpMetadata")));
        Object server = first(call, "mcp_server", "mcpServer", "server");
        if (server == null) {
            server = first(definition, "mcp_server", "mcpServer", "server");
        }
        if (server != null) {
            out.put("server", String.valueOf(server));
        }
        Object tool = first(call, "mcp_tool", "mcpTool");
        if (tool == null) {
            tool = first(definition, "mcp_tool", "mcpTool");
        }
        if (tool != null) {
            out.put("tool", String.valueOf(tool));
        }
        Object sessionId = first(call, "mcp_session_id", "mcpSessionId");
        if (sessionId == null) {
            sessionId = first(definition, "mcp_session_id", "mcpSessionId");
        }
        if (sessionId != null) {
            out.put("session_id", String.valueOf(sessionId));
        }
        return out;
    }

    private static List<Map<String, Object>> conflicts(
            List<Map<String, Object>> calls,
            Map<String, Object> tools,
            Collection<String> conflictKeys) {
        Set<String> keys = new LinkedHashSet<>(conflictKeys);
        Object configured = first(tools, "conflict_keys", "conflictKeys");
        if (configured instanceof List<?> list) {
            for (Object key : list) {
                keys.add(String.valueOf(key));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> sortedKeys = keys.stream().sorted().toList();
        for (String key : sortedKeys) {
            Map<String, List<String>> buckets = new LinkedHashMap<>();
            for (Map<String, Object> call : calls) {
                Map<String, Object> result = record(first(call, "result", "output"));
                if (!result.containsKey(key)) {
                    continue;
                }
                buckets.computeIfAbsent(stableValue(result.get(key)), ignored -> new ArrayList<>())
                        .add(toolId(call));
            }
            if (buckets.size() > 1) {
                int total = buckets.values().stream().mapToInt(List::size).sum();
                int largest = buckets.values().stream().mapToInt(List::size).max().orElse(0);
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("key", key);
                conflict.put("values", buckets.keySet().stream().sorted().toList());
                conflict.put("tool_ids", buckets.values().stream()
                        .flatMap(Collection::stream)
                        .sorted()
                        .toList());
                conflict.put("confidence", total == 0 ? 1.0 : round((double) largest / total));
                out.add(conflict);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> validateSchema(
            Map<String, Object> value,
            Map<String, Object> schema,
            String label,
            String toolId,
            String toolName) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            for (Object field : list) {
                String key = String.valueOf(field);
                if (!value.containsKey(key)) {
                    out.add(violation("schema", toolId, toolName,
                            label + " missing required field " + key, Map.of()));
                }
            }
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (value.containsKey(key) && !matchesType(value.get(key), entry.getValue())) {
                    out.add(violation("schema", toolId, toolName,
                            label + "." + key + " has invalid type", Map.of()));
                }
            }
        }
        return out;
    }

    private static boolean matchesType(Object value, Object spec) {
        Object expected = spec instanceof Map<?, ?> map ? map.get("type") : spec;
        if (expected instanceof List<?> list) {
            for (Object item : list) {
                if (matchesType(value, item)) {
                    return true;
                }
            }
            return false;
        }
        return switch (String.valueOf(expected)) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number && !(value instanceof Boolean);
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static Map<String, Object> violation(
            String kind,
            String toolId,
            String toolName,
            String message,
            Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("tool_id", toolId);
        out.put("tool_name", toolName);
        out.put("message", message);
        out.putAll(extra);
        return out;
    }

    private static double overallConfidence(List<Map<String, Object>> conflicts, List<Double> values) {
        if (!conflicts.isEmpty()) {
            return conflicts.stream()
                    .map(c -> (Number) c.get("confidence"))
                    .mapToDouble(Number::doubleValue)
                    .min()
                    .orElse(1.0);
        }
        if (!values.isEmpty()) {
            return round(values.stream().mapToDouble(Double::doubleValue).average().orElse(1.0));
        }
        return 1.0;
    }

    private static String toolId(Map<String, Object> call) {
        Object value = first(call, "id", "tool_call_id", "toolCallId");
        return value == null ? toolName(call) : String.valueOf(value);
    }

    private static String toolName(Map<String, Object> call) {
        Object value = first(call, "name", "tool", "tool_name", "toolName");
        return value == null ? "tool" : String.valueOf(value);
    }

    private static Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    private static Map<String, Object> record(Object value) {
        return value instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<>();
    }

    private static Map<String, Object> copyMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Set.of("1", "true", "yes", "approved", "allow", "allowed")
                    .contains(s.toLowerCase());
        }
        return false;
    }

    private static Instant parseTime(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return Json.write(stableMap(map));
        }
        if (value instanceof List<?> list) {
            return Json.write(stableList(list));
        }
        return String.valueOf(value);
    }

    private static Object stableNormalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stableMap(map);
        }
        if (value instanceof List<?> list) {
            return stableList(list);
        }
        return value;
    }

    private static Map<String, Object> stableMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                .forEach(entry -> out.put(
                        String.valueOf(entry.getKey()),
                        stableNormalize(entry.getValue())));
        return out;
    }

    private static List<Object> stableList(List<?> input) {
        return input.stream().map(ToolRuntimeInterceptor::stableNormalize).toList();
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** Builder for {@link ToolRuntimeInterceptor}. */
    public static final class Builder {
        private OnFailure onFailure = OnFailure.WARN;
        private Double maxAgeSeconds;
        private final List<String> conflictKeys = new ArrayList<>();

        public Builder onFailure(OnFailure onFailure) {
            this.onFailure = onFailure;
            return this;
        }

        public Builder maxAgeSeconds(double maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
            return this;
        }

        public Builder conflictKey(String key) {
            this.conflictKeys.add(key);
            return this;
        }

        public ToolRuntimeInterceptor build() {
            return new ToolRuntimeInterceptor(this);
        }
    }
}
