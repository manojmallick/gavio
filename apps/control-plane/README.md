# Gavio Control Plane

Self-hosted control plane for local and private Gavio deployments.

```bash
cd apps/control-plane
npm start
```

Defaults:

- URL: `http://127.0.0.1:8787`
- Storage: JSON file at `.gavio-control-plane/state.json`
- Local admin role header: `x-gavio-role: owner`

The v3.1.0 app includes a dependency-light admin UI at `/` with views for
overview, projects/environments, runtime keys, policies/rollouts, budgets,
runtime events, audit records, workflow releases, enterprise admin, and
retention policies.

## Demo mode

Demo seeding is disabled by default. Enable it only for local/private demos:

```bash
GAVIO_CONTROL_PLANE_DEMO=1 npm start
```

Then use the UI `Seed demo` action or call:

```bash
curl -s -X POST http://127.0.0.1:8787/api/demo/seed \
  -H 'content-type: application/json' \
  -d '{}'
```

The response returns a one-time `gav_rt_...` runtime key plus the matching
`policySource`. Seeded records are metadata-only; content-bearing fields such
as `messages`, `content`, and `diff` are stripped before persistence.

## Storage modes

```bash
# Backwards-compatible JSON file store
GAVIO_CONTROL_PLANE_STORAGE=file \
GAVIO_CONTROL_PLANE_STATE=.gavio-control-plane/state.json \
npm start

# Durable SQLite store with startup migrations
GAVIO_CONTROL_PLANE_STORAGE=sqlite \
GAVIO_CONTROL_PLANE_SQLITE_PATH=.gavio-control-plane/control-plane.sqlite \
npm start

# Postgres store; install the optional driver first
npm install pg
GAVIO_CONTROL_PLANE_STORAGE=postgres \
GAVIO_CONTROL_PLANE_DATABASE_URL=postgres://gavio:gavio@localhost:5432/gavio \
npm start
```

SQLite and Postgres use the same migration-backed record schema for projects,
environments, runtime keys, policies, rollouts, budgets, runtime events, audit
records, config snapshots, enterprise admin records, retention policies, and
workflow releases. `/health` reports the active storage mode.

## Runtime config

Create a project, environment, policy, budget, runtime key, and rollout in the
UI or API, then load config from an SDK using:

```text
GET /api/runtime/config?policy_source=project:prod-support
Authorization: Bearer gav_rt_...
```

The response matches `spec/ControlPlaneRuntimeConfig.schema.json` and can be
cached by SDK clients for offline fail-open or fail-closed behavior.

## Workflow releases

Platform Workflow Release artifacts from `gavio workflow release` can be stored
directly or imported through the canonical import endpoint:

```bash
curl -s -X POST http://127.0.0.1:8787/api/workflow-releases/import \
  -H 'content-type: application/json' \
  -d @workflow-release.json
```

The import maps `workflowId`, release version, status, policy source, runtime
profile, hash, and evidence counts into a searchable control-plane record.
Release metadata is sanitized before storage.

## Enterprise admin

Enterprise Admin v2 adds OIDC/SAML-lite identity-provider metadata, scoped
admin API keys, rollout approvals, audit export, and retention controls without
adding external service dependencies.

Create scoped admin keys with `POST /api/admin-keys`. The plaintext
`gav_admin_...` token is returned once, the stored record keeps only a prefix
and SHA-256 hash, and later list responses redact the hash.

Useful scopes:

- `policy:write` creates policies and policy rollouts.
- `policy:approve` approves rollout gates.
- `audit:export` exports filtered audit records as JSON or JSONL.
- `retention:write` creates and applies retention policies.
- `identity:write` creates OIDC/SAML-lite provider metadata.
- `*` grants full admin control for private automation.

Approval-gated rollouts start as `pending_approval` until enough approval
records exist:

```text
POST /api/policy-rollouts/:id/approvals
Authorization: Bearer gav_admin_...
```

Retention can be evaluated safely before deletion:

```text
POST /api/retention/apply
Authorization: Bearer gav_admin_...
{ "dryRun": true }
```
