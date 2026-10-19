package io.gavio.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Helpers for the plain-Java JSON values ({@link Map}, {@link List}, strings, numbers,
 * booleans, {@code null}) that {@link Json} produces and consumes.
 *
 * <p>Tool-call arguments are such values. They can hold JSON {@code null}, which
 * {@code Map.copyOf} rejects, and their key order is significant (OpenAI-style APIs carry the
 * arguments as a string in insertion order), so these copies keep both.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** A deep, unmodifiable copy: maps keep insertion order, {@code null} values are allowed. */
    public static Object immutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), immutableCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(immutableCopy(item));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    /** {@link #immutableCopy(Object)} for an object; {@code null} becomes an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> immutableMap(Map<String, ?> map) {
        return map == null ? Map.of() : (Map<String, Object>) immutableCopy(map);
    }

    /** A deep, mutable copy, for building a provider payload that must not alias the caller's data. */
    public static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), mutableCopy(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(mutableCopy(item));
            }
            return out;
        }
        return value;
    }

    /** {@link #mutableCopy(Object)} for an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mutableMap(Map<String, ?> map) {
        return map == null ? new LinkedHashMap<>() : (Map<String, Object>) mutableCopy(map);
    }

    /**
     * Apply {@code fn} to every scalar leaf of a JSON value, keeping its structure.
     *
     * <p>Object keys are not visited: they come from the tool's declared schema, not from user
     * data. The result is a new (mutable) structure; the input is not modified.
     */
    public static Object mapLeaves(Object value, UnaryOperator<Object> fn) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), mapLeaves(entry.getValue(), fn));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(mapLeaves(item, fn));
            }
            return out;
        }
        return fn.apply(value);
    }

    /** {@link #mapLeaves(Object, UnaryOperator)} for an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapLeaves(Map<String, ?> map, UnaryOperator<Object> fn) {
        return (Map<String, Object>) mapLeaves((Object) map, fn);
    }
}
