package io.gavio.providers.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioRequest;
import org.junit.jupiter.api.Test;

class AzureOpenAiAdapterTest {
    @Test
    void providerNameAndHealth() {
        AzureOpenAiAdapter a = AzureOpenAiAdapter.builder().apiKey("k").endpoint("https://x").build();
        assertEquals("azure_openai", a.providerName());
        assertTrue(a.healthCheck().join());
    }

    @Test
    void buildsDeploymentUrl() {
        AzureOpenAiAdapter a = AzureOpenAiAdapter.builder()
                .apiKey("k").endpoint("https://my.openai.azure.com/")
                .deployment("gpt4o").apiVersion("2024-06-01").build();
        GavioRequest req = GavioRequest.builder().message("user", "x").model("gpt-4o").build();
        assertEquals(
                "https://my.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-06-01",
                a.url(req));
    }
}
