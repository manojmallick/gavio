package io.gavio.prompts;

import io.gavio.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic prompt-manifest canonicalization, digesting and signing. */
public final class PromptManifests {
    public static final String SCHEMA_VERSION = "gavio.prompt-registry.v2";
    public static final String SIGNATURE_ALGORITHM = "HMAC-SHA256";

    private PromptManifests() {
    }

    public static Map<String, Object> sign(Map<String, Object> manifest, String secret, String keyId) {
        Map<String, Object> signed = new LinkedHashMap<>(manifest);
        signed.put("signature", Map.of(
                "algorithm", SIGNATURE_ALGORITHM,
                "keyId", keyId,
                "value", signatureValue(manifest, secret)));
        return signed;
    }

    @SuppressWarnings("unchecked")
    public static boolean verifySignature(Map<String, Object> manifest, String secret) {
        Object rawSignature = manifest.get("signature");
        if (!(rawSignature instanceof Map<?, ?>)) {
            return false;
        }
        Map<String, Object> signature = (Map<String, Object>) rawSignature;
        if (!SIGNATURE_ALGORITHM.equals(signature.get("algorithm"))) {
            return false;
        }
        Object value = signature.get("value");
        return value instanceof String expected && expected.equals(signatureValue(manifest, secret));
    }

    public static String digest(Map<String, Object> manifest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = canonicalJson(manifest).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String canonicalJson(Map<String, Object> manifest) {
        Map<String, Object> unsigned = new LinkedHashMap<>(manifest);
        unsigned.remove("signature");
        return Json.write(canonicalize(unsigned));
    }

    private static String signatureValue(Map<String, Object> manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalJson(manifest).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PromptManifests::canonicalize).toList();
        }
        return value;
    }
}
