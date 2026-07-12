import io.gavio.prompts.EvalAssertion;
import io.gavio.prompts.EvalCase;
import io.gavio.prompts.EvalReport;
import io.gavio.prompts.EvalSuite;
import io.gavio.prompts.PromptRegistry;
import io.gavio.prompts.PromptTemplate;
import io.gavio.prompts.RenderedPrompt;
import io.gavio.types.Message;
import java.util.List;
import java.util.Map;

/** Gavio Prompt Registry + Evals - versioned templates and safe reports. */
public class PromptRegistryEvalsDemo {
    public static void main(String[] args) {
        PromptRegistry registry = new PromptRegistry();
        registry.register(new PromptTemplate(
                "support.reply",
                "2026-07-12",
                List.of(
                        Message.of("system", "You are a concise support assistant."),
                        Message.of("user", "Reply to {{ customer }} about {{ topic }}.")),
                List.of("customer", "topic"),
                Map.of()));

        RenderedPrompt rendered = registry.render(
                "support.reply",
                Map.of("customer", "Avery", "topic", "refund"));
        EvalSuite suite = new EvalSuite("support-smoke", List.of(
                new EvalCase(
                        "refund-pass",
                        "support.reply",
                        null,
                        Map.of("customer", "Avery", "topic", "refund"),
                        List.of(new EvalAssertion("contains", "refund", false)),
                        Map.of()),
                new EvalCase(
                        "refund-fail",
                        "support.reply",
                        null,
                        Map.of("customer", "Avery", "topic", "refund"),
                        List.of(new EvalAssertion("not_contains", "card number", false)),
                        Map.of())));
        String failureOutput = "Hello Avery, please send your card number.";
        Map<String, String> outputs = Map.of(
                "refund-pass", "Hello Avery, your refund is approved.",
                "refund-fail", failureOutput);

        EvalReport report = suite.run(registry, (prompt, testCase) -> outputs.get(testCase.id()));
        String serialized = report.toMap().toString();

        System.out.println("template=" + rendered.lineage().templateId()
                + "@" + rendered.lineage().templateVersion());
        System.out.println("score=" + report.score());
        System.out.println("passed=" + report.passedCases() + "/" + report.totalCases());
        System.out.println("output_hash_len=" + report.cases().get(0).outputHash().length());
        System.out.println("raw_output_stored=" + serialized.contains(failureOutput));
    }
}
