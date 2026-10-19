package io.gavio.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Cross-SDK canonical JSON (F-DX-13): the byte-for-byte form Gavio hashes.
 *
 * <p>Compact separators, object keys sorted by Unicode code point, non-ASCII emitted as-is,
 * control characters as lowercase four-digit unicode escapes, integral numbers written as
 * integers ({@code 1.0} becomes {@code 1}), and every other number as the shortest round-trip
 * decimal without an exponent. Pinned by {@code test-vectors/workflow/release.json}
 * ({@code canonicalJsonCases}) and, for tool-call arguments, by
 * {@code test-vectors/tool-calling/arguments-hash.json}.
 *
 * <p>A leaf class with no dependency on the workflow machinery, so request and audit code can
 * hash tool arguments without importing it.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** The canonical JSON text of {@code value} (maps, iterables, strings, numbers, booleans, null). */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    /** Lowercase hex SHA-256 of the canonical JSON of {@code value}, encoded as UTF-8. */
    public static String sha256Hex(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof CharSequence) {
            out.append(value instanceof Boolean ? value.toString() : Json.write(value.toString()));
        } else if (value instanceof Number number) {
            out.append(number(number));
        } else if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((left, right) ->
                    compareCodePoints(String.valueOf(left.getKey()), String.valueOf(right.getKey())));
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : entries) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(Json.write(String.valueOf(entry.getKey()))).append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot canonicalize " + value.getClass().getName());
        }
    }

    private static String number(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte || number instanceof BigInteger) {
            return number.toString();
        }
        BigDecimal decimal;
        if (number instanceof BigDecimal big) {
            decimal = big;
        } else {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("canonical JSON cannot represent NaN or Infinity");
            }
            if (d == Math.rint(d)) {
                return new BigDecimal(d).toBigInteger().toString();
            }
            decimal = new BigDecimal(number instanceof Float ? Float.toString((float) d) : Double.toString(d));
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }
}
