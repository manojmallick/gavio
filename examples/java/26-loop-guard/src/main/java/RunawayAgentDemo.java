import io.gavio.Gateway;
import io.gavio.GavioException.LoopGuardException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.interceptors.reliability.LoopGuard;
import io.gavio.interceptors.reliability.LoopGuardPolicy;
import io.gavio.providers.MockProvider;
import io.gavio.types.Provider;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * Gavio loop guard - stop a runaway agent (F-REL-09).
 *
 * <pre>{@code mvn -q compile exec:java}</pre>
 *
 * <p>A planner agent that never decides it is done keeps calling the model. The loop guard
 * counts the calls of one root run and refuses the next one once a limit is reached: first the
 * 25-step cap, then a cost cap. Offline: the mock provider, no key, no network.
 */
public class RunawayAgentDemo {

    private static final String MODEL = "demo-agent";
    // Both strings are 40 characters, about 10 tokens each. At $6 per 1,000 tokens every call
    // costs $0.12, so the cost cap below is reached after a handful of calls.
    private static final String PROMPT = "Plan the next step of the task, then act";
    private static final String REPLY = "Step done; planning the next step now...";

    public static void main(String[] args) {
        runAgent("Step cap: at most 25 calls per run",
                LoopGuardPolicy.builder("planner-steps").maxSteps(25).build(), 25, "max_steps");
        // $0.12 a call: the 9th call crosses $1.00 and still happens
        runAgent("Cost cap: $1.00 per run (the 25-step cap is still set)",
                LoopGuardPolicy.builder("planner-budget").maxSteps(25).maxCostUsd(1.00).build(),
                9, "max_cost");
    }

    private static void runAgent(
            String title, LoopGuardPolicy policy, int expectCalls, String expectBreach) {
        PricingProvider pricing = new PricingProvider(Map.of(MODEL, new double[] {6.0, 6.0}));
        Gateway gateway = Gateway.builder()
                .adapter(new MockProvider(REPLY, "mock-1", pricing))
                .model(MODEL)
                .use(LoopGuard.of(policy))
                .build();

        // Every build() makes a request with a fresh trace id. The request carries its own model
        // (the gateway's model is only filled in by the List<Message> overloads), and that model
        // is what prices the call.
        GavioRequest.Builder next = GavioRequest.builder()
                .message("user", PROMPT)
                .model(MODEL)
                .provider(Provider.MOCK)
                .agentId("planner");

        // The agent loop. The first call is the root of the run; every later call names it as
        // its parentTraceId, so the guard counts all of them as one run.
        GavioResponse root = gateway.complete(next.build()).join();
        int calls = 1;
        Map<String, Object> decision;
        try {
            while (true) { // a runaway agent: nothing in the loop ever decides to stop
                gateway.complete(next.parentTraceId(root.traceId()).build()).join();
                calls++;
            }
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof LoopGuardException stop)) {
                throw e;
            }
            decision = stop.decision(); // metadata only: counts, never prompts or tool arguments
        }

        System.out.println("== " + title);
        System.out.println("provider calls : " + calls);
        System.out.println("stop reason    : " + decision.get("breached")
                + " (" + decision.get("reason") + ", action " + decision.get("action") + ")");
        System.out.println(String.format(Locale.ROOT, "run so far     : %s steps, $%.2f",
                decision.get("steps"), ((Number) decision.get("costUsd")).doubleValue()));
        System.out.println("same run       : "
                + (root.traceId().equals(decision.get("runId")) ? "yes" : "no"));
        System.out.println();

        // The example doubles as a CI check: fail if the guard stopped the run anywhere else.
        if (calls != expectCalls || !expectBreach.equals(decision.get("breached"))) {
            throw new IllegalStateException("loop guard stopped at " + calls + " calls: " + decision);
        }
    }
}
