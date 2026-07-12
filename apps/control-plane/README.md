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

The default file store and SQLite mode use Node built-ins only; Postgres mode
requires the optional `pg` driver. Runtime keys are returned once at creation
time and stored as SHA-256 hashes. Events and audit records are metadata-first:
content-bearing fields such as `messages`, `content`, and `diff` are stripped
before persistence.

Enterprise Admin v2 adds OIDC/SAML-lite identity-provider metadata, scoped
admin API keys, rollout approvals, audit export, and retention controls without
adding external service dependencies.

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
records, and config snapshots. `/health` reports the active storage mode.

## Runtime Config

Create a project, environment, policy, budget, runtime key, and rollout, then
load config from an SDK using:

```text
GET /api/runtime/config?policy_source=project:prod-support
Authorization: Bearer gav_rt_...
```

The response matches `spec/ControlPlaneRuntimeConfig.schema.json` and can be
cached by SDK clients for offline fail-open or fail-closed behavior.

## Enterprise Admin v2

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
