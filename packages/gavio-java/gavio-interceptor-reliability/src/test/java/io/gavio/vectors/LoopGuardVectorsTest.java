package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.RootRunResolver;
import io.gavio.interceptors.reliability.LoopGuard;
import io.gavio.interceptors.reliability.LoopGuardDecision;
import io.gavio.interceptors.reliability.LoopGuardPolicy;
import io.gavio.interceptors.reliability.LoopGuardState;
import io.gavio.json.CanonicalJson;
import io.gavio.json.Json;
import io.gavio.types.ToolCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Loop guard (F-REL-09): runs the shared {@code test-vectors/loop-guard/decisions.json} — the same
 * file the Python and JavaScript SDKs run — against the Java policy, evaluator, run-state
 * accumulation and root-run resolver. Values are compared as canonical JSON, so {@code 1000} and
 * {@code 1000.0} are the same number.
 */
class LoopGuardVectorsTest {

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate " + relative + " from working dir");
    }

    private static Map<String, Object> json(String relative) throws IOException {
        return Json.parseObject(Files.readString(repoFile(relative)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String key) throws IOException {
        return (List<Map<String, Object>>) json("test-vectors/loop-guard/decisions.json").get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static String canonical(Object value) {
        return CanonicalJson.write(value);
    }

    @TestFactory
    Iterable<DynamicTest> decisionVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases("decisionCases")) {
            tests.add(DynamicTest.dynamicTest("decision: " + c.get("id"), () -> {
                Map<String, Object> state = map(c.get("state"));
                Map<String, Object> decision = LoopGuard.evaluate(
                        LoopGuardPolicy.from(map(c.get("policy"))),
                        LoopGuardState.fromWire(state),
                        "run-1",
                        ((Number) state.get("elapsedSeconds")).doubleValue()).toMap();
                for (Map.Entry<String, Object> expected : map(c.get("expected")).entrySet()) {
                    assertTrue(decision.containsKey(expected.getKey()), expected.getKey());
                    assertEquals(canonical(expected.getValue()), canonical(decision.get(expected.getKey())),
                            expected.getKey());
                }
                for (Object key : list(c.getOrDefault("expectedAbsent", List.of()))) {
                    assertFalse(decision.containsKey(key), String.valueOf(key));
                }
                assertEquals(map(c.get("policy")).get("id"), decision.get("policyId"));
                assertEquals("run-1", decision.get("runId"));
            }));
        }
        return tests;
    }

    /** Play a run: decide before each call, record the calls that went through. */
    private static List<Map<String, Object>> drive(Map<String, Object> c) {
        LoopGuardPolicy policy = LoopGuardPolicy.from(map(c.get("policy")));
        LoopGuardState state = new LoopGuardState();
        Double startedAt = null;
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Object raw : list(c.get("calls"))) {
            Map<String, Object> call = map(raw);
            double at = ((Number) call.get("atSeconds")).doubleValue();
            if (startedAt == null) {
                startedAt = at;
            }
            LoopGuardDecision decision = LoopGuard.evaluate(policy, state, "run-1", at - startedAt);
            decisions.add(decision.toMap());
            if (!"stop".equals(decision.action())) { // a refused call never happened
                List<ToolCall> toolCalls = new ArrayList<>();
                List<Object> items = list(call.getOrDefault("toolCalls", List.of()));
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> item = map(items.get(i));
                    toolCalls.add(new ToolCall("c" + i, (String) item.get("name"), map(item.get("arguments"))));
                }
                state.record(((Number) call.get("costUsd")).doubleValue(), toolCalls);
            }
        }
        return decisions;
    }

    private static List<Object> column(List<Map<String, Object>> decisions, String key) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> decision : decisions) {
            out.add(decision.get(key));
        }
        return out;
    }

    @TestFactory
    Iterable<DynamicTest> runVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases("runCases")) {
            tests.add(DynamicTest.dynamicTest("run: " + c.get("id"), () -> {
                List<Map<String, Object>> decisions = drive(c);
                assertEquals(canonical(c.get("expectedActions")), canonical(column(decisions, "action")));
                assertEquals(canonical(c.get("expectedBreached")), canonical(column(decisions, "breached")));
                assertEquals(canonical(c.get("expectedSteps")), canonical(column(decisions, "steps")));
                assertEquals(canonical(c.get("expectedCostUsd")), canonical(column(decisions, "costUsd")));
                assertEquals(canonical(c.get("expectedElapsedSeconds")),
                        canonical(column(decisions, "elapsedSeconds")));
                if (c.containsKey("expectedTargetModels")) {
                    List<Object> targets = new ArrayList<>();
                    for (Map<String, Object> decision : decisions) {
                        targets.add(decision.get("targetModel")); // absent -> null
                    }
                    assertEquals(canonical(c.get("expectedTargetModels")), canonical(targets));
                }
            }));
        }
        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> rootRunVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases("rootRunCases")) {
            tests.add(DynamicTest.dynamicTest("root run: " + c.get("id"), () -> {
                RootRunResolver resolver = c.containsKey("capacity")
                        ? new RootRunResolver(((Number) c.get("capacity")).intValue())
                        : new RootRunResolver();
                List<Object> resolved = new ArrayList<>();
                for (Object raw : list(c.get("calls"))) {
                    Map<String, Object> call = map(raw);
                    resolved.add(resolver.resolve(
                            (String) call.get("traceId"),
                            (String) call.get("parentTraceId"),
                            call.get("metadata") == null ? null : map(call.get("metadata"))));
                }
                assertEquals(c.get("expectedRunIds"), resolved);
            }));
        }
        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> policyErrorVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases("policyErrorCases")) {
            tests.add(DynamicTest.dynamicTest("invalid policy: " + c.get("id"), () -> {
                assertEquals("Configuration", c.get("expectedError"));
                assertThrows(ConfigurationException.class, () -> LoopGuardPolicy.from(map(c.get("policy"))));
            }));
        }
        return tests;
    }

    @Test
    void vectorsAndSchemasAgree() throws IOException {
        Map<String, Object> decisionSchema = json("spec/LoopGuardDecision.schema.json");
        Map<String, Object> policySchema = json("spec/LoopGuardPolicy.schema.json");
        Set<Object> allowed = map(decisionSchema.get("properties")).keySet().stream()
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> c : cases("decisionCases")) {
            Map<String, Object> state = map(c.get("state"));
            Map<String, Object> decision = LoopGuard.evaluate(
                    LoopGuardPolicy.from(map(c.get("policy"))), LoopGuardState.fromWire(state), "r",
                    ((Number) state.get("elapsedSeconds")).doubleValue()).toMap();
            assertTrue(decision.keySet().containsAll(list(decisionSchema.get("required"))), String.valueOf(c.get("id")));
            assertTrue(allowed.containsAll(decision.keySet()), String.valueOf(c.get("id")));
            assertTrue(map(policySchema.get("properties")).keySet().containsAll(map(c.get("policy")).keySet()));
            assertTrue(map(policySchema.get("properties")).keySet()
                    .containsAll(LoopGuardPolicy.from(map(c.get("policy"))).toMap().keySet()));
        }
        Set<Object> breaches = new LinkedHashSet<>(list(map(map(
                map(decisionSchema.get("properties")).get("breaches")).get("items")).get("enum")));
        assertEquals(Set.of("max_steps", "max_cost", "max_wall_time", "repeated_tool_call"), breaches);
        assertEquals(List.of("id", "action"), policySchema.get("required"));

        // The audit schema defines the loop_guard entry the Java record writes (as loopGuard on the wire).
        Map<String, Object> entry = map(list(map(map(json("spec/AuditRecord.schema.json")
                .get("properties")).get("loopGuard")).get("anyOf")).get(0));
        assertEquals(Set.of("policyId", "runId", "action", "steps"), Set.copyOf(list(entry.get("required"))));
    }
}
