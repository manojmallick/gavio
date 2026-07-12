# Self-hosted Control Plane

Since: `1.7.0`; durable persistence since `2.3.0`; Enterprise Admin v2 since `2.6.0`

The Gavio control plane is optional and self-hosted. It manages runtime
projects, environments, hashed runtime keys, policy rollout, budgets, audit
search, and config snapshots while keeping SDK runtime packages dependency
light. v2.3.0 adds storage adapters for the control-plane app: the default JSON
file store, migration-backed SQLite for durable local/private deployments, and
a Postgres adapter path for managed database deployments. v2.6.0 adds
Enterprise Admin v2 for OIDC/SAML-lite identity-provider metadata, scoped admin
API keys, rollout approvals, audit export, and retention controls.

## Local server

```bash
cd apps/control-plane
npm start
```

The local API listens on `http://127.0.0.1:8787` by default and stores state in
`.gavio-control-plane/state.json`.

## Storage

| Mode | Configuration | Notes |
|---|---|---|
| JSON file | default, or `GAVIO_CONTROL_PLANE_STORAGE=file` with `GAVIO_CONTROL_PLANE_STATE=.gavio-control-plane/state.json` | Backwards-compatible local store. |
| SQLite | `GAVIO_CONTROL_PLANE_STORAGE=sqlite` with `GAVIO_CONTROL_PLANE_SQLITE_PATH=.gavio-control-plane/control-plane.sqlite` | Runs idempotent migrations on startup and persists projects, environments, keys, policies, rollouts, budgets, events, audit records, and config snapshots. Requires a Node runtime with `node:sqlite` support. |
| Postgres | `GAVIO_CONTROL_PLANE_STORAGE=postgres` with `GAVIO_CONTROL_PLANE_DATABASE_URL=postgres://...` | Uses the same migration contract. The optional `pg` driver must be installed in `apps/control-plane` before enabling this mode. |

SQLite example:

```bash
cd apps/control-plane
GAVIO_CONTROL_PLANE_STORAGE=sqlite \
GAVIO_CONTROL_PLANE_SQLITE_PATH=.gavio-control-plane/control-plane.sqlite \
npm start
```

Postgres example:

```bash
cd apps/control-plane
npm install pg
GAVIO_CONTROL_PLANE_STORAGE=postgres \
GAVIO_CONTROL_PLANE_DATABASE_URL=postgres://gavio:gavio@localhost:5432/gavio \
npm start
```

Migrations are intentionally idempotent: every startup checks the
`gavio_control_plane_migrations` table and applies missing schema changes
before serving requests.

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
| `/api/admin-keys` | Scoped admin API keys; plaintext is returned once, hashes are stored |
| `/api/teams` | Team and user scopes |
| `/api/identity-providers` | OIDC/SAML-lite provider metadata, domain hints, and role mapping |
| `/api/policies` | Policy documents and policy-pack references |
| `/api/policy-rollouts` | Active, paused, or rolled-back assignments |
| `/api/policy-rollouts/:id/approvals` | Approval records that can activate gated rollouts |
| `/api/policy-approvals` | Searchable rollout approval records |
| `/api/budgets` | Cost governance limits |
| `/api/events` | Metadata-only runtime events |
| `/api/audit-records` | Admin audit and searchable audit records |
| `/api/audit-export` | Filtered JSON or JSONL audit export |
| `/api/config-snapshots` | Point-in-time runtime config snapshots |
| `/api/retention-policies` | Retention windows for events, audit records, and config snapshots |
| `/api/retention/apply` | Dry-run or apply active retention policies |

## Enterprise Admin v2

Feature ID: `F-ADMIN-02`

Enterprise Admin v2 is intentionally metadata-first. The control plane stores
OIDC/SAML provider descriptors and role mappings, not full SSO protocol
state. Sensitive identity-provider fields such as `clientSecret`,
`privateKey`, and `signingSecret` are hashed before persistence.

Create a scoped admin key:

```bash
curl -s http://127.0.0.1:8787/api/admin-keys \
  -H 'content-type: application/json' \
  -d '{
    "name": "enterprise ops automation",
    "scopes": ["policy:write", "policy:approve", "audit:export", "retention:write"]
  }'
```

The response includes `gav_admin_...` once. Later list responses include only
the key prefix, scopes, and metadata. Stored keys use SHA-256 hashes.

Supported admin scopes:

| Scope | Allows |
|---|---|
| `admin:keys.read` | List scoped admin keys |
| `admin:keys.write` | Create runtime or admin keys |
| `admin:write` | Create generic admin resources |
| `identity:write` | Create OIDC/SAML-lite provider metadata |
| `policy:write` | Create policies and policy rollouts |
| `policy:approve` | Approve rollout gates |
| `audit:export` | Export audit records as JSON or JSONL |
| `retention:write` | Create and apply retention policies |
| `*` | Full control-plane admin key |

Approval-gated rollout:

```bash
curl -s http://127.0.0.1:8787/api/policy-rollouts \
  -H "authorization: Bearer $GAVIO_ADMIN_KEY" \
  -H 'content-type: application/json' \
  -d '{
    "id": "rollout_support_prod",
    "projectId": "proj_support",
    "environment": "prod",
    "policySource": "project:prod-support",
    "policyId": "pol_support",
    "requiresApproval": true,
    "requiredApprovals": 1
  }'

curl -s http://127.0.0.1:8787/api/policy-rollouts/rollout_support_prod/approvals \
  -H "authorization: Bearer $GAVIO_ADMIN_KEY" \
  -H 'content-type: application/json' \
  -d '{ "decision": "approved", "metadata": { "ticket": "SEC-42" } }'
```

Rollouts that require approval start as `pending_approval`, so runtime config
continues to ignore them until enough `approved` records exist.

Audit export:

```bash
curl -s 'http://127.0.0.1:8787/api/audit-export?format=jsonl&tenant=acme' \
  -H "authorization: Bearer $GAVIO_ADMIN_KEY"
```

Retention:

```bash
curl -s http://127.0.0.1:8787/api/retention-policies \
  -H "authorization: Bearer $GAVIO_ADMIN_KEY" \
  -H 'content-type: application/json' \
  -d '{ "resource": "events", "maxAgeDays": 30 }'

curl -s http://127.0.0.1:8787/api/retention/apply \
  -H "authorization: Bearer $GAVIO_ADMIN_KEY" \
  -H 'content-type: application/json' \
  -d '{ "dryRun": true }'
```

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
Enterprise admin requests can also use `Authorization: Bearer gav_admin_...`
with one of the scopes above. Local role headers remain available for
self-hosted development and private deployments.

## Privacy

The control plane strips content-bearing fields before storing events and audit
records. By default it keeps metadata such as trace, tenant, feature, model,
provider, risk, policy decision, cost, and timing, not raw prompts or responses.
Enterprise Admin v2 applies the same metadata-only posture to approval metadata
and audit exports.
