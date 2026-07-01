package io.gavio.interceptors.governance;

import io.gavio.GavioException.ModelNotAllowedException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** ModelPolicy (F-GOV-04) — per-role model allowlists (RBAC). */
public final class ModelPolicy implements Interceptor {

    private final Map<String, List<String>> roles;
    private final String defaultRole;
    private final String roleKey;

    private ModelPolicy(Builder b) {
        this.roles = b.roles;
        this.defaultRole = b.defaultRole;
        this.roleKey = b.roleKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "model_policy";
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        Object roleObj = request.metadata().get(roleKey);
        String role = roleObj != null ? String.valueOf(roleObj) : defaultRole;
        List<String> allowed = roles.getOrDefault(role, List.of());
        if (allowed.contains("*") || allowed.contains(request.model())) {
            return CompletableFuture.completedFuture(request);
        }
        return CompletableFuture.failedFuture(new ModelNotAllowedException(role, request.model()));
    }

    /** Builder for {@link ModelPolicy}. */
    public static final class Builder {
        private final Map<String, List<String>> roles = new HashMap<>();
        private String defaultRole = "default";
        private String roleKey = "role";

        public Builder role(String role, List<String> models) {
            this.roles.put(role, List.copyOf(models));
            return this;
        }

        public Builder defaultRole(String v) {
            this.defaultRole = v;
            return this;
        }

        public Builder roleKey(String v) {
            this.roleKey = v;
            return this;
        }

        public ModelPolicy build() {
            return new ModelPolicy(this);
        }
    }
}
