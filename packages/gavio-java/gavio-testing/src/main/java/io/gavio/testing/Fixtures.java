package io.gavio.testing;

import io.gavio.types.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic fixtures for tests. All PII here is fake — never real data. */
public final class Fixtures {

    private Fixtures() {
    }

    /** Synthetic PII samples keyed by entity type (structurally valid, not real). */
    public static final Map<String, String> PII_SAMPLES = buildSamples();

    private static Map<String, String> buildSamples() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("EMAIL", "jan.devries@example.com");
        m.put("IBAN", "NL91ABNA0417164300"); // valid mod-97
        m.put("BSN", "111222333"); // valid 11-proef
        m.put("CREDIT_CARD", "4111111111111111"); // valid Luhn
        m.put("PHONE", "+31 6 12345678");
        m.put("IP_ADDRESS", "192.168.1.42");
        m.put("SSN", "123-45-6789");
        m.put("SECRET", "sk-ant-abcdef0123456789ABCDEF0123");
        return Map.copyOf(m);
    }

    public static List<Message> sampleMessages(String content) {
        return List.of(Message.of("user", content));
    }

    public static List<Message> messageWithPii() {
        String text = "Email " + PII_SAMPLES.get("EMAIL") + " and transfer to "
                + PII_SAMPLES.get("IBAN") + " by Friday.";
        return sampleMessages(text);
    }
}
