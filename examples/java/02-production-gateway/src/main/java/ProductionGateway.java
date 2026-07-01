import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.sinks.StdoutSink;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.reliability.RetryInterceptor;
import io.gavio.interceptors.reliability.TimeoutPolicy;
import io.gavio.providers.MockProvider;
import io.gavio.types.Provider;
import io.gavio.types.Sensitivity;

/**
 * Gavio production gateway — a realistic interceptor stack.
 *
 * <p>audit (outermost) → PII guard → timeout → retry, in front of a real
 * provider. Falls back to the mock provider when no key is set.
 *
 * <pre>{@code
 *   export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY (optional)
 *   mvn -q compile exec:java
 * }</pre>
 */
public class ProductionGateway {

    public static void main(String[] args) {
        var builder = Gateway.builder()
                .use(AuditInterceptor.builder().sink(new StdoutSink(true)).build())   // outermost
                .use(PiiGuard.builder().sensitivity(Sensitivity.STRICT).build())
                .use(new TimeoutPolicy(30))
                .use(RetryInterceptor.builder().maxAttempts(3).baseDelayMs(500).build());

        Gateway gw;
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            gw = builder.provider(Provider.ANTHROPIC).model("claude-sonnet-4-6").build();
        } else if (System.getenv("OPENAI_API_KEY") != null) {
            gw = builder.provider(Provider.OPENAI).model("gpt-4o").build();
        } else {
            System.out.println("[info] No API key set — using MockProvider so the demo still runs.\n");
            gw = builder.adapter(new MockProvider()).model("mock").build();
        }

        GavioResponse r = gw.complete(GavioRequest.builder()
                .message("system", "You are a concise billing assistant.")
                .message("user", "Summarise the account for jan@example.com.")
                .agentId("billing-agent")
                .sessionId("sess-42")
                .build()).join();

        System.out.println();
        System.out.println("Reply       : " + r.content());
        System.out.println("Provider    : " + r.provider() + " " + r.modelVersion());
        System.out.println("Interceptors: " + r.interceptorsFired());
        System.out.printf("Tokens      : %d   Cost: $%.6f%n", r.usage().totalTokens(), r.costUsd());
    }
}
