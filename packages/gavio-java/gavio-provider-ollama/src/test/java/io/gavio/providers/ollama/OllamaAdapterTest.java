package io.gavio.providers.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OllamaAdapterTest {
    @Test
    void providerNameAndHealth() {
        OllamaAdapter a = new OllamaAdapter();
        assertEquals("ollama", a.providerName());
        assertTrue(a.healthCheck().join());
    }
}
