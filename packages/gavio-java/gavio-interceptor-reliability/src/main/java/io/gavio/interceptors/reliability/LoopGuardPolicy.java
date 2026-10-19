package io.gavio.interceptors.reliability;

import io.gavio.GavioException.ConfigurationException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limits for one root run of an agent loop (F-REL-09). Every limit is optional; {@code null} is
 * never breached. Shape: {@code spec/LoopGuardPolicy.schema.json}.
 *
 * <p>A limit is what the run may consume: it is checked before a call and reached once it is
 * consumed, so {@code maxSteps = 25} allows 25 calls and refuses the 26th.
 *
 * @param id policy id, non-empty
 * @param action what happens on a breach
 * @param maxSteps maximum provider calls in one run (at least 1)
 * @param maxCostUsd maximum recorded spend of one run in USD (at least 0)
 * @param maxWallTimeSeconds maximum seconds from the run's first call to the start of the next
 *     one (greater than 0)
 * @param maxRepeatedToolCalls stop a run that requests the same (tool, arguments) this many times
 *     (at least 2)
 * @param fallbackModel required when {@code action} is {@link Action#FALLBACK}
 */
public record LoopGuardPolicy(
        String id,
        Action action,
        Integer maxSteps,
        Double maxCostUsd,
        Double maxWallTimeSeconds,
        Integer maxRepeatedToolCalls,
        String fallbackModel,
        Map<String, Object> metadata) {

    /** What the guard does when a limit is reached. */
    public enum Action {
        /** Refuse the call with a {@code LoopGuardException}. */
        STOP("stop"),
        /** Let the call through on {@code fallbackModel}. */
        FALLBACK("fallback"),
        /** Record the breach and let the call through unchanged. */
        DRY_RUN("dry_run");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        /** The wire spelling: {@code stop}, {@code fallback} or {@code dry_run}. */
        public String value() {
            return value;
        }

        static Action fromValue(Object value) {
            for (Action action : values()) {
                if (action.value.equals(value)) {
                    return action;
                }
            }
            throw new ConfigurationException("loop guard action must be one of stop, fallback, dry_run");
        }
    }

    public LoopGuardPolicy {
        if (id == null || id.isEmpty()) {
            throw new ConfigurationException("loop guard policy id must be a non-empty string");
        }
        action = action == null ? Action.STOP : action;
        if (maxSteps != null && maxSteps < 1) {
            throw new ConfigurationException("maxSteps must be at least 1");
        }
        if (maxRepeatedToolCalls != null && maxRepeatedToolCalls < 2) {
            throw new ConfigurationException("maxRepeatedToolCalls must be at least 2");
        }
        if (maxCostUsd != null && !(Double.isFinite(maxCostUsd) && maxCostUsd >= 0)) {
            throw new ConfigurationException("maxCostUsd must be a number of at least 0");
        }
        if (maxWallTimeSeconds != null && !(Double.isFinite(maxWallTimeSeconds) && maxWallTimeSeconds > 0)) {
            throw new ConfigurationException("maxWallTimeSeconds must be a number greater than 0");
        }
        if (action == Action.FALLBACK && (fallbackModel == null || fallbackModel.isEmpty())) {
            throw new ConfigurationException("loop guard action 'fallback' needs a fallbackModel");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A policy with only an id and an action; add limits with {@link #builder(String)}. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Read a policy from its wire shape (camelCase; Python's snake_case spellings are accepted).
     *
     * @throws ConfigurationException for an invalid policy
     */
    public static LoopGuardPolicy from(Map<String, Object> wire) {
        Object id = wire.get("id");
        return new LoopGuardPolicy(
                id instanceof String text ? text : null,
                wire.containsKey("action") ? Action.fromValue(wire.get("action")) : Action.STOP,
                integer("maxSteps", pick(wire, "maxSteps", "max_steps")),
                number("maxCostUsd", pick(wire, "maxCostUsd", "max_cost_usd")),
                number("maxWallTimeSeconds", pick(wire, "maxWallTimeSeconds", "max_wall_time_seconds")),
                integer("maxRepeatedToolCalls", pick(wire, "maxRepeatedToolCalls", "max_repeated_tool_calls")),
                pick(wire, "fallbackModel", "fallback_model") instanceof String model ? model : null,
                wire.get("metadata") instanceof Map<?, ?> map ? stringKeys(map) : null);
    }

    /** The wire shape, with unset limits left out. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("action", action.value());
        putIfSet(out, "maxSteps", maxSteps);
        putIfSet(out, "maxCostUsd", maxCostUsd);
        putIfSet(out, "maxWallTimeSeconds", maxWallTimeSeconds);
        putIfSet(out, "maxRepeatedToolCalls", maxRepeatedToolCalls);
        putIfSet(out, "fallbackModel", fallbackModel);
        out.put("metadata", new LinkedHashMap<>(metadata));
        return out;
    }

    private static void putIfSet(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static Object pick(Map<String, Object> wire, String camel, String snake) {
        return wire.containsKey(camel) ? wire.get(camel) : wire.get(snake);
    }

    private static Integer integer(String name, Object value) {
        if (value == null) {
            return null;
        }
        boolean whole = value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte
                || value instanceof Double d && Double.isFinite(d) && d == Math.rint(d)
                || value instanceof Float f && Double.isFinite(f) && f == Math.rint(f);
        if (!whole && !(value instanceof BigInteger)) {
            throw new ConfigurationException(name + " must be an integer");
        }
        long asLong = ((Number) value).longValue();
        if (asLong > Integer.MAX_VALUE || asLong < Integer.MIN_VALUE) {
            throw new ConfigurationException(name + " is out of range");
        }
        return (int) asLong;
    }

    private static Double number(String name, Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n) || value instanceof Boolean) {
            throw new ConfigurationException(name + " must be a number");
        }
        return n.doubleValue();
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** Fluent builder for {@link LoopGuardPolicy}. */
    public static final class Builder {
        private final String id;
        private Action action = Action.STOP;
        private Integer maxSteps;
        private Double maxCostUsd;
        private Double maxWallTimeSeconds;
        private Integer maxRepeatedToolCalls;
        private String fallbackModel;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxCostUsd(double maxCostUsd) {
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        public Builder maxWallTimeSeconds(double maxWallTimeSeconds) {
            this.maxWallTimeSeconds = maxWallTimeSeconds;
            return this;
        }

        public Builder maxRepeatedToolCalls(int maxRepeatedToolCalls) {
            this.maxRepeatedToolCalls = maxRepeatedToolCalls;
            return this;
        }

        public Builder fallbackModel(String fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public LoopGuardPolicy build() {
            return new LoopGuardPolicy(id, action, maxSteps, maxCostUsd, maxWallTimeSeconds,
                    maxRepeatedToolCalls, fallbackModel, metadata);
        }
    }
}
