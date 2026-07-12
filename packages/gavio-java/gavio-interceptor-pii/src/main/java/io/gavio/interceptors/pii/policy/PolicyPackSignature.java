package io.gavio.interceptors.pii.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Signature metadata for a catalog Policy Pack manifest. */
public record PolicyPackSignature(String algorithm, String value, String keyId, String signedAt) {

    public Map<String, Object> manifest() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", algorithm);
        out.put("value", value);
        if (keyId != null) {
            out.put("keyId", keyId);
        }
        if (signedAt != null) {
            out.put("signedAt", signedAt);
        }
        return out;
    }
}
