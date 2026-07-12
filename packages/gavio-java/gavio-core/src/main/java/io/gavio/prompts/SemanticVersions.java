package io.gavio.prompts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SemanticVersions {
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private SemanticVersions() {
    }

    static Version parse(String value) {
        Matcher matcher = SEMVER.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return new Version(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    static boolean isValid(String value) {
        return parse(value) != null;
    }

    static void validate(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("invalid semantic version: " + value);
        }
    }

    static int compareStrings(String left, String right) {
        Version leftParsed = parse(left);
        Version rightParsed = parse(right);
        if (leftParsed == null || rightParsed == null) {
            return left.compareTo(right);
        }
        return leftParsed.compareTo(rightParsed);
    }

    static boolean matchesSelector(String version, String selector) {
        Version parsed = parse(version);
        if (parsed == null) {
            return false;
        }
        if ("*".equals(selector) || "latest".equals(selector)) {
            return true;
        }
        if (selector.startsWith("^")) {
            Version base = parse(selector.substring(1));
            if (base == null || parsed.compareTo(base) < 0) {
                return false;
            }
            if (base.major > 0) {
                return parsed.major == base.major;
            }
            if (base.minor > 0) {
                return parsed.major == 0 && parsed.minor == base.minor;
            }
            return parsed.major == base.major && parsed.minor == base.minor && parsed.patch == base.patch;
        }
        if (selector.startsWith("~")) {
            Version base = parse(selector.substring(1));
            return base != null
                    && parsed.compareTo(base) >= 0
                    && parsed.major == base.major
                    && parsed.minor == base.minor;
        }
        String[] constraints = selector.trim().split("\\s+");
        if (constraints.length == 0) {
            return false;
        }
        for (String constraint : constraints) {
            if (!matchesConstraint(parsed, constraint)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesConstraint(Version version, String constraint) {
        for (String operator : new String[] {">=", "<=", ">", "<", "="}) {
            if (!constraint.startsWith(operator)) {
                continue;
            }
            Version base = parse(constraint.substring(operator.length()));
            if (base == null) {
                return false;
            }
            int cmp = version.compareTo(base);
            return switch (operator) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
        }
        Version base = parse(constraint);
        return base != null && version.compareTo(base) == 0;
    }

    record Version(int major, int minor, int patch, String prerelease) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int majorCmp = Integer.compare(major, other.major);
            if (majorCmp != 0) {
                return majorCmp;
            }
            int minorCmp = Integer.compare(minor, other.minor);
            if (minorCmp != 0) {
                return minorCmp;
            }
            int patchCmp = Integer.compare(patch, other.patch);
            if (patchCmp != 0) {
                return patchCmp;
            }
            if (java.util.Objects.equals(prerelease, other.prerelease)) {
                return 0;
            }
            if (prerelease == null) {
                return 1;
            }
            if (other.prerelease == null) {
                return -1;
            }
            return prerelease.compareTo(other.prerelease);
        }
    }
}
