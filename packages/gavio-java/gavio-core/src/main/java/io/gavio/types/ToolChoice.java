package io.gavio.types;

import io.gavio.GavioException.ConfigurationException;
import java.util.Map;

/**
 * How the model may use the tools a request declares (F-ADP-03): {@code auto} lets it decide,
 * {@code none} forbids tool calls, {@code required} forces some tool call, and {@code tool}
 * forces the named tool. On the wire it is {@code "auto" | "none" | "required" | {"name": ...}}.
 *
 * <p>Ignored when the request declares no tools.
 */
public record ToolChoice(Mode mode, String name) {

    /** The four kinds of choice. */
    public enum Mode {
        AUTO("auto"),
        NONE("none"),
        REQUIRED("required"),
        TOOL("tool");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private static final ToolChoice AUTO = new ToolChoice(Mode.AUTO, null);
    private static final ToolChoice NONE = new ToolChoice(Mode.NONE, null);
    private static final ToolChoice REQUIRED = new ToolChoice(Mode.REQUIRED, null);

    public ToolChoice {
        if (mode == null) {
            throw new ConfigurationException("toolChoice mode must not be null");
        }
        if (mode == Mode.TOOL && (name == null || name.isEmpty())) {
            throw new ConfigurationException("toolChoice {name} needs a tool name");
        }
        if (mode != Mode.TOOL) {
            name = null;
        }
    }

    public static ToolChoice auto() {
        return AUTO;
    }

    public static ToolChoice none() {
        return NONE;
    }

    public static ToolChoice required() {
        return REQUIRED;
    }

    /** Force the model to call the named tool. */
    public static ToolChoice tool(String name) {
        return new ToolChoice(Mode.TOOL, name);
    }

    /**
     * Normalise a wire-shaped choice: {@code null}, a {@link ToolChoice}, one of the strings
     * {@code "auto"} / {@code "none"} / {@code "required"}, or a map with a {@code name}.
     *
     * @throws ConfigurationException for anything else
     */
    public static ToolChoice parse(Object choice) {
        if (choice == null) {
            return null;
        }
        if (choice instanceof ToolChoice resolved) {
            return resolved;
        }
        if (choice instanceof String text) {
            return switch (text) {
                case "auto" -> AUTO;
                case "none" -> NONE;
                case "required" -> REQUIRED;
                default -> throw new ConfigurationException(
                        "unsupported toolChoice \"" + text + "\" (use auto, none, required or {name})");
            };
        }
        if (choice instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.isEmpty()) {
            return tool(name);
        }
        throw new ConfigurationException("toolChoice must be auto, none, required or {name: <tool>}");
    }

    /** The wire shape: a string, or {@code {"name": <tool>}} for a named tool. */
    public Object toWire() {
        return mode == Mode.TOOL ? Map.of("name", name) : mode.value();
    }
}
