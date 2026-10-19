package io.gavio.interceptors.reliability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loop guard's verdict on one provider call (F-REL-09). Metadata only: counts, a cost and a
 * time — never prompts, responses or tool arguments. Shape: {@code spec/LoopGuardDecision.schema.json}.
 *
 * @param allowed false only for action {@code stop}
 * @param action {@code allow}, {@code stop}, {@code fallback} or {@code dry_run}
 * @param breached the first limit reached ({@code max_steps}, {@code max_cost},
 *     {@code max_wall_time}, {@code repeated_tool_call}), or {@code null}
 * @param breaches every limit reached, in that order
 * @param reason {@code within_limits} or {@code <breached>_reached}
 * @param steps provider calls the run had made before this one
 * @param costUsd recorded spend of the run before this call, rounded to 8 places
 * @param elapsedSeconds seconds since the run's first call, rounded to 8 places
 * @param repeatedToolCall the first tool call (in first-seen order) that reached the repeat limit
 * @param targetModel the model to use instead; set only when {@code action} is {@code fallback}
 */
public record LoopGuardDecision(
        String policyId,
        String runId,
        boolean allowed,
        String action,
        String breached,
        List<String> breaches,
        String reason,
        int steps,
        double costUsd,
        double elapsedSeconds,
        RepeatedToolCall repeatedToolCall,
        String targetModel) {

    /** A tool call a run keeps requesting: its name, arguments hash and request count. */
    public record RepeatedToolCall(String name, String argumentsHash, int count) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("argumentsHash", argumentsHash);
            out.put("count", count);
            return out;
        }
    }

    public LoopGuardDecision {
        breaches = List.copyOf(breaches);
    }

    /** This verdict downgraded to a recorded breach: the call goes through unchanged. */
    LoopGuardDecision asDryRun() {
        return new LoopGuardDecision(policyId, runId, true, "dry_run", breached, breaches, reason,
                steps, costUsd, elapsedSeconds, repeatedToolCall, null);
    }

    /** The spec (camelCase) shape; {@code targetModel} is present only for a fallback. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policyId", policyId);
        out.put("runId", runId);
        out.put("allowed", allowed);
        out.put("action", action);
        out.put("breached", breached);
        out.put("breaches", new ArrayList<>(breaches));
        out.put("reason", reason);
        out.put("steps", steps);
        out.put("costUsd", costUsd);
        out.put("elapsedSeconds", elapsedSeconds);
        out.put("repeatedToolCall", repeatedToolCall == null ? null : repeatedToolCall.toMap());
        if (targetModel != null) {
            out.put("targetModel", targetModel);
        }
        return out;
    }
}
