package io.gavio.providers.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.types.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiAdapterTest {
    @Test
    void providerName() {
        assertEquals("gemini", new GeminiAdapter(null, null, 30, null).providerName());
    }

    @Test
    void mapsRolesAndSystem() {
        Map.Entry<String, List<Object>> r = GeminiAdapter.toContents(List.of(
                Message.of("system", "be terse"),
                Message.of("user", "hi"),
                Message.of("assistant", "hello")));
        assertEquals("be terse", r.getKey());
        assertEquals(2, r.getValue().size());
        assertEquals("user", ((Map<?, ?>) r.getValue().get(0)).get("role"));
        assertEquals("model", ((Map<?, ?>) r.getValue().get(1)).get("role"));
    }

    @Test
    void healthCheck() {
        assertTrue(GeminiAdapter.builder().apiKey("k").build().healthCheck().join());
        assertFalse(new GeminiAdapter(null, null, 30, null).healthCheck().join());
    }
}
