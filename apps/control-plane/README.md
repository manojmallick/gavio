# Gavio Control Plane

Self-hosted control plane for local and private Gavio deployments.

```bash
cd apps/control-plane
npm start
```

Defaults:

- URL: `http://127.0.0.1:8787`
- State file: `.gavio-control-plane/state.json`
- Local admin role header: `x-gavio-role: owner`

The app uses Node built-ins only. Runtime keys are returned once at creation
time and stored as SHA-256 hashes. Events and audit records are metadata-first:
content-bearing fields such as `messages`, `content`, and `diff` are stripped
before persistence.

## Runtime Config

Create a project, environment, policy, budget, runtime key, and rollout, then
load config from an SDK using:

```text
GET /api/runtime/config?policy_source=project:prod-support
Authorization: Bearer gav_rt_...
```

The response matches `spec/ControlPlaneRuntimeConfig.schema.json` and can be
cached by SDK clients for offline fail-open or fail-closed behavior.
