package io.gavio.interceptors.guardrails;

import io.gavio.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * License / copyright detection validator (F-QUA-10).
 *
 * <p>Flags known open-source license text (MIT, Apache-2.0, GPL-2.0/3.0,
 * BSD-3-Clause, MPL-2.0) in a model response before it lands in user code.
 * Matches against a shipped corpus of hashed 8-word shingles — no license text
 * is ever bundled. Detections surface in the guardrail outcome and, via the
 * guardrails interceptor, in the audit record.
 */
public final class LicenseDetectorValidator implements OutputValidator {

    private static final int SHINGLE_N = 8;
    private static final Map<String, List<String>> FINGERPRINTS = loadFingerprints();

    private final Collection<String> licenses;
    private final int minMatches;

    public LicenseDetectorValidator() {
        this(null, 1);
    }

    public LicenseDetectorValidator(Collection<String> licenses, int minMatches) {
        this.licenses = licenses;
        this.minMatches = minMatches;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> loadFingerprints() {
        try (InputStream in =
                LicenseDetectorValidator.class.getResourceAsStream("/license-fingerprints.json")) {
            if (in == null) {
                throw new IllegalStateException("license-fingerprints.json not on classpath");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(text);
            Map<String, Object> licenses = (Map<String, Object>) root.get("licenses");
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : licenses.entrySet()) {
                List<String> hashes = new ArrayList<>();
                for (Object h : (List<Object>) e.getValue()) {
                    hashes.add((String) h);
                }
                out.put(e.getKey(), hashes);
            }
            return Collections.unmodifiableMap(out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** ASCII-lower-alnum tokeniser — must stay byte-identical across all SDKs. */
    static List<String> normalizeTokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                cur.append((char) (c + 32));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static Set<String> shingleHashes(List<String> tokens) {
        Set<String> hashes = new HashSet<>();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        for (int i = 0; i + SHINGLE_N <= tokens.size(); i++) {
            StringBuilder gram = new StringBuilder();
            for (int j = 0; j < SHINGLE_N; j++) {
                if (j > 0) {
                    gram.append(' ');
                }
                gram.append(tokens.get(i + j));
            }
            byte[] digest = md.digest(gram.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int b = 0; b < 8; b++) {
                hex.append(Character.forDigit((digest[b] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(digest[b] & 0xf, 16));
            }
            hashes.add(hex.toString());
        }
        return hashes;
    }

    /** Returns the sorted SPDX ids whose fingerprint appears in {@code content}. */
    public List<String> detect(String content) {
        Set<String> present = shingleHashes(normalizeTokens(content));
        Collection<String> ids = licenses != null ? licenses : FINGERPRINTS.keySet();
        Set<String> found = new TreeSet<>();
        for (String id : ids) {
            List<String> fingerprints = FINGERPRINTS.get(id);
            if (fingerprints == null) {
                continue;
            }
            int hits = 0;
            for (String h : fingerprints) {
                if (present.contains(h)) {
                    hits++;
                    if (hits >= minMatches) {
                        break;
                    }
                }
            }
            if (hits >= minMatches) {
                found.add(id);
            }
        }
        return new ArrayList<>(found);
    }

    @Override
    public String name() {
        return "license_detector";
    }

    @Override
    public ValidationResult validate(String content) {
        List<String> found = detect(content);
        if (found.isEmpty()) {
            return ValidationResult.passed();
        }
        return ValidationResult.failed("license text detected: " + String.join(", ", found));
    }
}
