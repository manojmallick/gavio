---
description: "Self-hosted Gavio Control Plane — local/private admin UI, runtime config, policy rollout, budget config, audit search, workflow releases, and SDK cache fallback."
---

# Self-hosted Control Plane

Since: `1.7.0`; durable persistence since `2.3.0`; Enterprise Admin v2 since `2.6.0`; UX v2 since `3.1.0`

The Gavio control plane is optional and self-hosted. It manages runtime
projects, environments, hashed runtime keys, policy rollout, budgets, audit
search, and config snapshots while keeping SDK runtime packages dependency
light. v2.3.0 adds storage adapters for the control-plane app: the default JSON
file store, migration-backed SQLite for durable local/private deployments, and
a Postgres adapter path for managed database deployments. v2.6.0 adds
Enterprise Admin v2 for OIDC/SAML-lite identity-provider metadata, scoped admin
API keys, rollout approvals, audit export, and retention controls. v3.1.0 adds
the Control Plane UX v2 admin UI, `/api/overview`, opt-in demo seeding, and
canonical Platform Workflow Release import.

## Local server

```bash
cd apps/control-plane
npm start
```

The local API and UI listen on `http://127.0.0.1:8787` by default and store
state in `.gavio-control-plane/state.json`.

The UI includes views for overview, projects/environments, runtime keys,
policies/rollouts, budgets, runtime events, audit records, workflow releases,
enterprise admin, and retention policies. It uses the same API as automation,
so records created in the UI are available to SDK runtime config calls.

## Demo mode

Demo seeding is disabled unless explicitly enabled:

```bash
cd apps/control-plane
GAVIO_CONTROL_PLANE_DEMO=1 npm start
```

Use the UI `Seed demo` action or call:

```bash
curl -s -X POST http://127.0.0.1:8787/api/demo/seed \
  -H 'content-type: application/json' \
  -d '{}'
```

The response includes a one-time `gav_rt_...` key and a `policySource` that can
be passed directly into the SDK examples. Demo records strip content-bearing
fields before storage and are intended for local/private use only.

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
| `/api/overview` | Counts, recent events/audit, active rollouts, latest workflow releases |
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
| `/api/workflow-releases` | Searchable Platform Workflow Release records |
| `/api/workflow-releases/import` | Canonical metadata-only Platform Workflow Release import |
| `/api/demo/seed` | Local-only demo records when `GAVIO_CONTROL_PLANE_DEMO=1` |

Search filters include trace id, tenant, feature, model, provider, risk,
policy source, release version, status, resource, and resource id where those
fields apply.

## Workflow releases

Platform Workflow Release artifacts from `gavio workflow release` can be stored
directly or imported through the canonical endpoint:

```bash
curl -s -X POST http://127.0.0.1:8787/api/workflow-releases/import \
  -H 'content-type: application/json' \
  -d @workflow-release.json
```

The import requires `schemaVersion:
gavio.platform-workflow-release.v1` and maps `workflowId`, release version,
status, reasons, policy source, runtime profile, workflow hash, and evidence
counts into a control-plane record. Metadata is sanitized before persistence.

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

The control plane strips content-bearing fields before storing events, audit
records, approvals, and workflow releases. By default it keeps metadata such as
trace, tenant, feature, model, provider, risk, policy decision, cost, timing,
release status, and workflow hash, not raw prompts or responses. Enterprise
Admin v2 applies the same metadata-only posture to approval metadata and audit
exports.
