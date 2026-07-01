package io.gavio.interceptors.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.PiiBlockedException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.types.PiiMode;
import io.gavio.types.Provider;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class PiiGuardTest {

    private static InterceptorContext ctx() {
        return new InterceptorContext("t-1");
    }

    private static GavioRequest reqWith(String content) {
        return GavioRequest.builder()
                .message("user", content)
                .model("mock")
                .provider(Provider.MOCK)
                .build();
    }

    @Test
    void redactsAndRecordsEntityTypes() {
        PiiGuard guard = new PiiGuard();
        InterceptorContext ctx = ctx();
        GavioRequest req = guard.before(reqWith("mail jan@example.com now"), ctx).join();
        assertFalse(req.messages().get(0).content().contains("jan@example.com"));
        assertTrue(req.messages().get(0).content().contains("[EMAIL_1]"));
        assertTrue(ctx.piiEntityTypes().contains("EMAIL"));
    }

    @Test
    void restoreOnResponse() {
        PiiGuard guard = new PiiGuard();
        InterceptorContext ctx = ctx();
        guard.before(reqWith("mail jan@example.com now"), ctx).join();
        GavioResponse resp = GavioResponse.builder()
                .traceId("t-1").content("I emailed [EMAIL_1] for you.")
                .model("mock").provider("mock").build();
        GavioResponse restored = guard.after(resp, ctx).join();
        assertEquals("I emailed jan@example.com for you.", restored.content());
    }

    @Test
    void blockModeRaises() {
        PiiGuard guard = PiiGuard.builder().mode(PiiMode.BLOCK).build();
        CompletionException ex = assertThrows(CompletionException.class,
                () -> guard.before(reqWith("mail jan@example.com now"), ctx()).join());
        assertTrue(ex.getCause() instanceof PiiBlockedException);
    }

    @Test
    void maskModeNoRestore() {
        PiiGuard guard = PiiGuard.builder().mode(PiiMode.MASK).build();
        InterceptorContext ctx = ctx();
        GavioRequest req = guard.before(reqWith("mail jan@example.com now"), ctx).join();
        assertFalse(req.messages().get(0).content().contains("jan@example.com"));
        assertTrue(req.messages().get(0).content().contains("*"));
    }

    @Test
    void dryRunDoesNotModifyButStillRecords() {
        PiiGuard guard = new PiiGuard();
        InterceptorContext ctx = ctx();
        ctx.dryRun(true);
        GavioRequest req = guard.before(reqWith("mail jan@example.com now"), ctx).join();
        assertTrue(req.messages().get(0).content().contains("jan@example.com")); // unmodified
        assertTrue(ctx.piiEntityTypes().contains("EMAIL")); // but detected
    }

    @Test
    void noPiiPassthrough() {
        PiiGuard guard = new PiiGuard();
        InterceptorContext ctx = ctx();
        GavioRequest req = guard.before(reqWith("just a normal sentence"), ctx).join();
        assertEquals("just a normal sentence", req.messages().get(0).content());
        assertTrue(ctx.piiEntityTypes().isEmpty());
    }
}
