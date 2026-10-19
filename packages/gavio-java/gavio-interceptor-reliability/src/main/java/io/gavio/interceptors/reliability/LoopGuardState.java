package io.gavio.interceptors.reliability;

import io.gavio.interceptors.reliability.LoopGuardDecision.RepeatedToolCall;
import io.gavio.types.ToolCall;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the guard remembers about one root run (F-REL-09). Not thread-safe: {@link LoopGuard}
 * serialises access.
 *
 * <p>Tool calls are counted by (name, arguments hash) in first-seen order; the hash is
 * {@link ToolCall#argumentsHash()}, so repeats are found without keeping the arguments.
 * {@link #startedAt()} is whatever clock the caller uses; this class never reads one.
 */
public final class LoopGuardState {

    private record ToolKey(String name, String argumentsHash) {
    }

    private int steps;
    private double costUsd;
    private Double startedAt;
    private final Map<ToolKey, Integer> toolCalls = new LinkedHashMap<>();

    public int steps() {
        return steps;
    }

    public double costUsd() {
        return costUsd;
    }

    /** Seconds on the caller's clock of the run's first call, or {@code null} before it. */
    public Double startedAt() {
        return startedAt;
    }

    void startedAt(double startedAt) {
        this.startedAt = startedAt;
    }

    /**
     * Count a call the guard let through, as soon as it starts. Counting at the start means a
     * loop of calls that fail at the provider still reaches {@code maxSteps}.
     */
    public void startCall() {
        steps++;
    }

    /** Add what a finished call cost and the tool calls its response asked for. */
    public void addResult(double callCostUsd, Iterable<ToolCall> calls) {
        costUsd = round8(costUsd + Math.max(callCostUsd, 0.0));
        for (ToolCall call : calls) {
            toolCalls.merge(new ToolKey(call.name(), call.argumentsHash()), 1, Integer::sum);
        }
    }

    /** Record one call that went through: allowed, fallback or dry_run, never a refused one. */
    public void record(double callCostUsd, Iterable<ToolCall> calls) {
        startCall();
        addResult(callCostUsd, calls);
    }

    /** The counted tool calls in first-seen order. */
    public List<RepeatedToolCall> toolCallCounts() {
        List<RepeatedToolCall> out = new ArrayList<>();
        toolCalls.forEach((key, count) -> out.add(new RepeatedToolCall(key.name(), key.argumentsHash(), count)));
        return out;
    }

    /** Read the state shape the shared vectors use ({@code elapsedSeconds} is passed separately). */
    public static LoopGuardState fromWire(Map<String, Object> wire) {
        LoopGuardState state = new LoopGuardState();
        state.steps = wire.get("steps") instanceof Number n ? n.intValue() : 0;
        state.costUsd = wire.get("costUsd") instanceof Number n ? n.doubleValue() : 0.0;
        if (wire.get("toolCalls") instanceof List<?> items) {
            for (Object raw : items) {
                if (raw instanceof Map<?, ?> item) {
                    state.toolCalls.merge(
                            new ToolKey(String.valueOf(item.get("name")), String.valueOf(item.get("argumentsHash"))),
                            ((Number) item.get("count")).intValue(),
                            Integer::sum);
                }
            }
        }
        return state;
    }

    static double round8(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_EVEN).doubleValue();
    }
}
