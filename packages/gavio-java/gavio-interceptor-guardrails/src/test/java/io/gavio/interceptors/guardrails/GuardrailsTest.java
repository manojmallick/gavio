package io.gavio.interceptors.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gavio.Gateway;
import io.gavio.GavioException.GuardrailViolationException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.providers.MockProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class GuardrailsTest {

    private static Gateway gw(String response, Interceptor ic) {
        return Gateway.builder().adapter(new MockProvider(response)).model("mock").use(ic).build();
    }

    private static GavioRequest req() {
        return GavioRequest.builder().message("user", "q").model("mock").build();
    }

    @Test
    void jsonSchemaPasses() {
        var schema = Map.<String, Object>of("type", "object", "required", List.of("answer"));
        Gateway gw = gw(
                "{\"answer\":\"42\"}",
                GuardrailsInterceptor.builder().validator(new JsonSchemaValidator(schema)).build());
        assertEquals("{\"answer\":\"42\"}", gw.complete(req()).join().content());
    }

    @Test
    void jsonSchemaFailRaises() {
        var schema = Map.<String, Object>of("type", "object", "required", List.of("answer"));
        Gateway gw = gw(
                "{\"wrong\":1}",
                GuardrailsInterceptor.builder().validator(new JsonSchemaValidator(schema)).build());
        CompletionException ex = assertThrows(CompletionException.class, () -> gw.complete(req()).join());
        assertInstanceOf(GuardrailViolationException.class, ex.getCause());
    }

    @Test
    void invalidJsonFails() {
        Gateway gw = gw(
                "not json",
                GuardrailsInterceptor.builder()
                        .validator(new JsonSchemaValidator(Map.of("type", "object")))
                        .build());
        assertThrows(CompletionException.class, () -> gw.complete(req()).join());
    }

    @Test
    void regexDenylistBlocks() {
        Gateway gw = gw(
                "contact competitor_name",
                GuardrailsInterceptor.builder().validator(RegexDenylistValidator.of("(?i)competitor_name")).build());
        assertThrows(CompletionException.class, () -> gw.complete(req()).join());
    }

    @Test
    void regexAllowlistRequiresMatch() {
        Gateway gw = gw(
                "hello",
                GuardrailsInterceptor.builder().validator(RegexAllowlistValidator.of("^\\{.*\\}$")).build());
        assertThrows(CompletionException.class, () -> gw.complete(req()).join());
    }

    @Test
    void warnModeReturnsResponse() {
        Gateway gw = gw(
                "bad output",
                GuardrailsInterceptor.builder()
                        .validator(RegexDenylistValidator.of("bad"))
                        .onFailure(GuardrailsInterceptor.OnFailure.WARN)
                        .build());
        assertEquals("bad output", gw.complete(req()).join().content());
    }
}
