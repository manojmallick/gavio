package io.gavio.interceptors.guardrails;

import io.gavio.json.Json;
import java.util.List;
import java.util.Map;

/**
 * JsonSchemaValidator (F-QUA-01) — a pragmatic zero-dependency JSON Schema
 * subset: type, required, properties, items, enum.
 */
public final class JsonSchemaValidator implements OutputValidator {

    private final Map<String, Object> schema;

    public JsonSchemaValidator(Map<String, Object> schema) {
        this.schema = schema;
    }

    @Override
    public String name() {
        return "json_schema";
    }

    @Override
    public ValidationResult validate(String content) {
        Object instance;
        try {
            instance = Json.parse(content);
        } catch (RuntimeException e) {
            return ValidationResult.failed("output is not valid JSON");
        }
        String err = check(instance, schema, "$");
        return err == null ? ValidationResult.passed() : ValidationResult.failed(err);
    }

    @SuppressWarnings("unchecked")
    private static String check(Object instance, Map<String, Object> schema, String path) {
        Object expectedObj = schema.get("type");
        String expected = expectedObj != null ? expectedObj.toString() : null;
        if (expected != null && !typeMatches(expected, instance)) {
            return path + ": expected type " + expected;
        }
        if (schema.containsKey("enum")) {
            List<Object> en = (List<Object>) schema.get("enum");
            if (en.stream().noneMatch(e -> java.util.Objects.equals(e, instance))) {
                return path + ": value not in enum";
            }
        }
        if ("object".equals(expected) && instance instanceof Map<?, ?> obj) {
            Object req = schema.get("required");
            if (req instanceof List<?> required) {
                for (Object key : required) {
                    if (!obj.containsKey(key)) {
                        return path + ": missing required property '" + key + "'";
                    }
                }
            }
            Object props = schema.get("properties");
            if (props instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> e : properties.entrySet()) {
                    if (obj.containsKey(e.getKey())) {
                        String err = check(
                                obj.get(e.getKey()),
                                (Map<String, Object>) e.getValue(),
                                path + "." + e.getKey());
                        if (err != null) {
                            return err;
                        }
                    }
                }
            }
        }
        if ("array".equals(expected) && instance instanceof List<?> arr && schema.containsKey("items")) {
            Map<String, Object> items = (Map<String, Object>) schema.get("items");
            for (int i = 0; i < arr.size(); i++) {
                String err = check(arr.get(i), items, path + "[" + i + "]");
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }

    private static boolean typeMatches(String expected, Object v) {
        return switch (expected) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Long || v instanceof Integer;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            default -> true;
        };
    }
}
