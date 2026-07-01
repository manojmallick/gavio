package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** IP address scanner — IPv4 and IPv6, validated structurally. */
public final class IpAddressScanner implements PiiScanner {

    private static final String IPV4 = "(?:\\d{1,3}\\.){3}\\d{1,3}";
    // Permissive IPv6 candidate — allows empty groups for "::" compression.
    private static final String IPV6 = "(?:[A-Fa-f0-9]{0,4}:){2,7}[A-Fa-f0-9]{0,4}";
    private static final Pattern IP =
            Pattern.compile("(?<![\\w.])(?:" + IPV6 + "|" + IPV4 + ")(?![\\w.])");

    @Override
    public String entityType() {
        return "IP_ADDRESS";
    }

    static boolean validIp(String candidate) {
        return validIpv4(candidate) || validIpv6(candidate);
    }

    private static boolean validIpv4(String s) {
        if (s.indexOf(':') >= 0) {
            return false;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean validIpv6(String s) {
        if (s.indexOf(':') < 0) {
            return false;
        }
        // At most one "::" compression marker.
        int doubleColon = countOccurrences(s, "::");
        if (doubleColon > 1) {
            return false;
        }
        boolean hasCompression = doubleColon == 1;
        String[] groups = s.split(":", -1);
        int realGroups = 0;
        int emptyGroups = 0;
        for (String g : groups) {
            if (g.isEmpty()) {
                emptyGroups++;
                continue;
            }
            if (g.length() > 4) {
                return false;
            }
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) {
                    return false;
                }
            }
            realGroups++;
        }
        if (hasCompression) {
            // With "::" we need fewer than 8 explicit groups.
            return realGroups <= 7;
        }
        // Full form: exactly 8 groups, no empty groups (no leading/trailing colon).
        return realGroups == 8 && emptyGroups == 0;
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = s.indexOf(sub, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + sub.length();
        }
        return count;
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = IP.matcher(text);
        while (m.find()) {
            if (!validIp(m.group())) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[IP_ADDRESS_" + idx + "]")
                    .build());
        }
        return out;
    }
}
