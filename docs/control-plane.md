# Self-hosted Control Plane

Since: `1.7.0`

The Gavio control plane is optional and self-hosted. It manages runtime
projects, environments, hashed runtime keys, policy rollout, budgets, audit
search, and config snapshots while keeping SDK runtime packages dependency
light.

## Local server

```bash
cd apps/control-plane
npm start
```

The local API listens on `http://127.0.0.1:8787` by default and stores state in
`.gavio-control-plane/state.json`.

## Runtime config flow

1. Create a project, environment, policy, budget, runtime key, and policy
   rollout.
2. Give the application only the returned `gav_rt_...` runtime key.
3. Configure the SDK with the control-plane URL, runtime key, and
   `policy_source`.
4. The SDK caches the last successful config and can fail open or closed during
   outages.

Python:

```python
gateway = (
    Gateway.builder()
    .dev_mode(True)
    .control_plane(
        "http://127.0.0.1:8787",
        runtime_key,
        "project:prod-support",
        fail_mode="open",
    )
    .build()
)
```

JavaScript:

```js
const gateway = await Gateway.fromConfig({
  devMode: true,
  control_plane: {
    url: "http://127.0.0.1:8787",
    runtime_key: runtimeKey,
    policy_source: "project:prod-support",
    fail_mode: "open",
  },
})
```

Java:

```java
Gateway gateway = Gateway.builder()
    .devMode(true)
    .controlPlane("http://127.0.0.1:8787", runtimeKey, "project:prod-support")
    .build();
```

## API resources

| Resource | Purpose |
|---|---|
| `/api/projects` | Runtime project scopes |
| `/api/environments` | Environment scopes such as `dev`, `staging`, `prod` |
| `/api/keys` | Runtime keys; plaintext is returned once, hashes are stored |
| `/api/teams` | Team and user scopes |
| `/api/policies` | Policy documents and policy-pack references |
| `/api/policy-rollouts` | Active, paused, or rolled-back assignments |
| `/api/budgets` | Cost governance limits |
| `/api/events` | Metadata-only runtime events |
| `/api/audit-records` | Admin audit and searchable audit records |
| `/api/config-snapshots` | Point-in-time runtime config snapshots |

## RBAC

Local admin requests use `x-gavio-role`:

| Role | Policy changes | Read/search |
|---|---:|---:|
| `owner` | yes | yes |
| `admin` | yes | yes |
| `developer` | events only | yes |
| `auditor` | no | yes |
| `read-only` | no | yes |

Runtime config requests use `Authorization: Bearer gav_rt_...`.

## Privacy

The control plane strips content-bearing fields before storing events and audit
records. By default it keeps metadata such as trace, tenant, feature, model,
provider, risk, policy decision, cost, and timing, not raw prompts or responses.
