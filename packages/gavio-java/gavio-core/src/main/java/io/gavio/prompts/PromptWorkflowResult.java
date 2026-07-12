package io.gavio.prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collection of prompt/eval links and their gate results. */
public record PromptWorkflowResult(List<PromptEvalLink> links, List<PromptVersionGate> gates) {

    public PromptWorkflowResult {
        links = List.copyOf(links);
        gates = List.copyOf(gates);
    }

    public boolean passed() {
        return gates.stream().allMatch(PromptVersionGate::passed);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", passed());
        List<Object> linkMaps = new ArrayList<>();
        for (PromptEvalLink link : links) {
            linkMaps.add(link.toMap());
        }
        out.put("links", linkMaps);
        List<Object> gateMaps = new ArrayList<>();
        for (PromptVersionGate gate : gates) {
            gateMaps.add(gate.toMap());
        }
        out.put("gates", gateMaps);
        return out;
    }
}
