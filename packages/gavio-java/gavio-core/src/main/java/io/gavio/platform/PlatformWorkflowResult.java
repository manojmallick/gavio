package io.gavio.platform;

import java.util.List;
import java.util.Map;

/** Built workflow release artifact plus gate status. */
public record PlatformWorkflowResult(Map<String, Object> artifact, boolean passed, List<String> reasons) {

    public PlatformWorkflowResult {
        reasons = List.copyOf(reasons);
    }
}
