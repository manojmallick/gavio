package io.gavio.prompts;

import io.gavio.inspector.Inspector;
import java.util.LinkedHashMap;
import java.util.Map;

/** Result of {@link EvalRunner#run}: the report, its CI gate, and linked prompt gates. */
public record EvalRunResult(
        EvalReport report,
        EvalGate gate,
        String source,
        PromptWorkflowResult workflow) {

    public boolean passed() {
        return gate.passed() && (workflow == null || workflow.passed());
    }

    /** Metadata-safe wire form; matches the Python {@code EvalRunResult.to_dict}. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>(report.toMap());
        out.put("schemaVersion", EvalRunner.SCHEMA_VERSION);
        Map<String, Object> runner = new LinkedHashMap<>();
        runner.put("name", "gavio-eval-runner");
        runner.put("version", Inspector.SDK_VERSION);
        runner.put("java", System.getProperty("java.version"));
        out.put("runner", runner);
        out.put("source", source);
        out.put("gate", gate.toMap());
        if (workflow != null) {
            out.put("workflow", workflow.toMap());
        }
        return out;
    }
}
