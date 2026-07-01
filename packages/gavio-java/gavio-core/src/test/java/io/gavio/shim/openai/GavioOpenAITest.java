package io.gavio.shim.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.types.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class GavioOpenAITest {
    @Test
    void returnsOpenAiShapedCompletion() {
        GavioOpenAI client = new GavioOpenAI(Gateway.builder().devMode(true).build());
        GavioOpenAI.ChatCompletion resp =
                client.chat().completions().create(List.of(Message.of("user", "hi there")), "mock");
        assertEquals("assistant", resp.choices().get(0).message().role());
        assertTrue(resp.choices().get(0).message().content().contains("hi there"));
        assertEquals(1, resp.choices().size());
    }
}
