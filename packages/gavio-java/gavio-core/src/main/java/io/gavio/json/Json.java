package io.gavio.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny hand-rolled JSON writer/parser so providers stay dependency-free.
 *
 * <p>Supports the subset needed for LLM request/response payloads: objects,
 * arrays, strings, numbers, booleans and null. Not a general-purpose library.
 */
public final class Json {

    private Json() {
    }

    // ---- writing -----------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- parsing -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("expected JSON object at top level");
        }
        return (Map<String, Object>) v;
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("trailing content after JSON value at " + p.pos);
        }
        return v;
    }

    /** Thrown on malformed JSON. */
    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObjectValue();
                case '[' -> parseArrayValue();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObjectValue() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at " + pos);
                }
            }
            return map;
        }

        private List<Object> parseArrayValue() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new JsonException("invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("invalid literal at " + pos);
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("invalid literal at " + pos);
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("invalid number at " + start);
            }
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(num);
            }
        }

        private char peek() {
            return atEnd() ? '\0' : s.charAt(pos);
        }

        private char next() {
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }
}
