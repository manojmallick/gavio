package io.gavio.interceptors.audit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Multi-agent DAG trace reconstruction (F-OBS-03). */
public final class AuditTrace {

    private AuditTrace() {}

    /** A node in the multi-agent call graph. */
    public static final class TraceNode {
        public final String traceId;
        public final String agentId;
        public final String parentTraceId;
        public final List<TraceNode> children = new ArrayList<>();

        TraceNode(String traceId, String agentId, String parentTraceId) {
            this.traceId = traceId;
            this.agentId = agentId;
            this.parentTraceId = parentTraceId;
        }
    }

    /**
     * Reconstruct the multi-agent DAG from audit records using parentTraceId +
     * traceId. Returns the root nodes (those with no known parent).
     */
    public static List<TraceNode> buildCallGraph(List<AuditRecord> records) {
        Map<String, TraceNode> nodes = new HashMap<>();
        for (AuditRecord rec : records) {
            nodes.put(rec.traceId(), new TraceNode(rec.traceId(), rec.agentId(), rec.parentTraceId()));
        }
        List<TraceNode> roots = new ArrayList<>();
        for (TraceNode node : nodes.values()) {
            TraceNode parent = node.parentTraceId != null ? nodes.get(node.parentTraceId) : null;
            if (parent != null) {
                parent.children.add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }
}
