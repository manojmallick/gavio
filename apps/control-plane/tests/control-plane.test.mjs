import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, test } from 'node:test'
import { startControlPlane } from '../src/server.mjs'

let server
let base
let hasNodeSqlite = false

try {
  await import('node:sqlite')
  hasNodeSqlite = true
} catch {
  hasNodeSqlite = false
}

before(async () => {
  const dir = mkdtempSync(join(tmpdir(), 'gavio-control-plane-'))
  const started = await startControlPlane({ port: 0, statePath: join(dir, 'state.json') })
  server = started.server
  base = started.url
})

after(() => {
  server.close()
})

test('creates rollout config with hashed runtime keys and snapshots', async () => {
  await post('/api/projects', { id: 'proj_support', name: 'Support' })
  await post('/api/environments', { id: 'env_prod', projectId: 'proj_support', name: 'prod' })
  await post('/api/policies', {
    id: 'pol_support',
    name: 'Support policy',
    policyPack: 'support',
    rules: [{ id: 'support-email', action: 'redact' }],
  })
  await post('/api/budgets', {
    id: 'budget_support',
    projectId: 'proj_support',
    scopeType: 'project',
    limitUsd: 25,
    window: 'day',
    action: 'warn',
  })
  const key = await post('/api/keys', {
    id: 'key_support_prod',
    projectId: 'proj_support',
    environment: 'prod',
    name: 'prod runtime',
  })
  await post('/api/policy-rollouts', {
    id: 'rollout_support_prod',
    projectId: 'proj_support',
    environment: 'prod',
    policySource: 'project:prod-support',
    policyId: 'pol_support',
    cacheTtlSeconds: 120,
  })

  assert.match(key.token, /^gav_rt_/)
  assert.equal(key.keyHash, undefined)
  const listedKeys = await get('/api/keys')
  assert.equal(listedKeys.items[0].keyHash, undefined)

  const config = await get('/api/runtime/config?policy_source=project:prod-support', {
    authorization: `Bearer ${key.token}`,
  })
  assert.equal(config.schemaVersion, '1.0')
  assert.equal(config.projectId, 'proj_support')
  assert.equal(config.environment, 'prod')
  assert.equal(config.policy.policyPack, 'support')
  assert.equal(config.budgets[0].limitUsd, 25)
  assert.equal(config.cache.failMode, 'open')

  const snapshots = await get('/api/config-snapshots')
  assert.equal(snapshots.items.length, 1)
  assert.equal(snapshots.items[0].policySource, 'project:prod-support')
})

test('RBAC blocks read-only policy mutation', async () => {
  const response = await fetch(`${base}/api/policies`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-gavio-role': 'auditor' },
    body: JSON.stringify({ id: 'pol_denied', name: 'Denied', rules: [] }),
  })
  assert.equal(response.status, 403)
})

test('runtime-key event ingestion rejects invalid bearer tokens', async () => {
  const response = await fetch(`${base}/api/events`, {
    method: 'POST',
    headers: { authorization: 'Bearer gav_rt_invalid', 'content-type': 'application/json' },
    body: JSON.stringify({ id: 'evt_bad_key', kind: 'runtime.event' }),
  })
  assert.equal(response.status, 401)
})

test('search filters events and strips content-bearing metadata', async () => {
  await post('/api/events', {
    id: 'evt_001',
    kind: 'runtime.event',
    traceId: 'trace-001',
    tenant: 'acme',
    feature: 'support-chat',
    model: 'gpt-4o',
    provider: 'openai',
    risk: 'high',
    metadata: {
      decision: { action: 'flag' },
      content: 'raw prompt must not persist',
    },
  })
  await post('/api/events', {
    id: 'evt_002',
    traceId: 'trace-002',
    tenant: 'other',
    feature: 'billing',
    model: 'claude-sonnet-4-6',
    provider: 'anthropic',
    risk: 'low',
  })
  const result = await get(
    '/api/events?trace=trace-001&tenant=acme&feature=support-chat&model=gpt-4o&provider=openai&risk=high',
  )
  assert.equal(result.items.length, 1)
  assert.equal(result.items[0].id, 'evt_001')
  assert.deepEqual(result.items[0].metadata, { decision: { action: 'flag' } })
})

test('enterprise admin v2 handles scoped keys, approvals, audit export, and retention', async () => {
  await post('/api/identity-providers', {
    id: 'idp_acme_oidc',
    protocol: 'oidc',
    issuer: 'https://login.acme.example',
    clientId: 'gavio-admin',
    clientSecret: 'do-not-store-plaintext',
    domainHints: ['acme.example'],
    roleMapping: { admins: 'admin', auditors: 'auditor' },
  })
  const providers = await get('/api/identity-providers')
  const provider = providers.items.find((item) => item.id === 'idp_acme_oidc')
  assert.equal(provider.protocol, 'oidc')
  assert.equal(provider.clientSecret, undefined)
  assert.match(provider.clientSecretHash, /^[a-f0-9]{64}$/)

  const adminKey = await post('/api/admin-keys', {
    id: 'adminkey_enterprise_ops',
    name: 'Enterprise ops automation',
    scopes: ['admin:keys.read', 'audit:export', 'policy:approve', 'policy:write', 'retention:write'],
  })
  assert.match(adminKey.token, /^gav_admin_/)
  assert.equal(adminKey.keyHash, undefined)
  const listedAdminKeys = await get('/api/admin-keys')
  const listedAdminKey = listedAdminKeys.items.find((item) => item.id === 'adminkey_enterprise_ops')
  assert.equal(listedAdminKey.keyHash, undefined)
  assert.equal(listedAdminKey.token, undefined)

  const auditOnlyKey = await post('/api/admin-keys', {
    id: 'adminkey_audit_only',
    name: 'Audit export only',
    scopes: ['audit:export'],
  })

  await post('/api/projects', { id: 'proj_enterprise', name: 'Enterprise' })
  await post('/api/environments', { id: 'env_enterprise_prod', projectId: 'proj_enterprise', name: 'prod' })
  await post(
    '/api/policies',
    {
      id: 'pol_enterprise',
      name: 'Enterprise policy',
      policyPack: 'enterprise',
      rules: [{ id: 'enterprise-redact', action: 'redact' }],
    },
    { authorization: `Bearer ${adminKey.token}` },
  )
  const runtimeKey = await post('/api/keys', {
    id: 'key_enterprise_prod',
    projectId: 'proj_enterprise',
    environment: 'prod',
    name: 'prod runtime',
  })
  const pendingRollout = await post(
    '/api/policy-rollouts',
    {
      id: 'rollout_enterprise_prod',
      projectId: 'proj_enterprise',
      environment: 'prod',
      policySource: 'project:enterprise-prod',
      policyId: 'pol_enterprise',
      requiresApproval: true,
      requiredApprovals: 1,
    },
    { authorization: `Bearer ${adminKey.token}` },
  )
  assert.equal(pendingRollout.status, 'pending_approval')

  const pendingConfig = await fetch(`${base}/api/runtime/config?policy_source=project:enterprise-prod`, {
    headers: { authorization: `Bearer ${runtimeKey.token}` },
  })
  assert.equal(pendingConfig.status, 404)

  const deniedApproval = await fetch(`${base}/api/policy-rollouts/rollout_enterprise_prod/approvals`, {
    method: 'POST',
    headers: { authorization: `Bearer ${auditOnlyKey.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ decision: 'approved' }),
  })
  assert.equal(deniedApproval.status, 403)

  const approvalResult = await post(
    '/api/policy-rollouts/rollout_enterprise_prod/approvals',
    {
      decision: 'approved',
      metadata: {
        ticket: 'SEC-42',
        content: 'approval notes must not persist',
      },
    },
    { authorization: `Bearer ${adminKey.token}` },
  )
  assert.equal(approvalResult.rollout.status, 'active')
  assert.equal(approvalResult.rollout.approvalCount, 1)
  assert.deepEqual(approvalResult.approval.metadata, { ticket: 'SEC-42' })

  const activeConfig = await get('/api/runtime/config?policy_source=project:enterprise-prod', {
    authorization: `Bearer ${runtimeKey.token}`,
  })
  assert.equal(activeConfig.policy.id, 'pol_enterprise')

  await post('/api/audit-records', {
    id: 'audit_enterprise_export',
    kind: 'admin.audit',
    actor: 'owner',
    action: 'review',
    resource: 'policyRollouts',
    resourceId: 'rollout_enterprise_prod',
    traceId: 'trace-enterprise-audit',
    tenant: 'acme',
    feature: 'enterprise-admin',
    metadata: {
      decision: { status: 'approved' },
      content: 'raw audit content must not export',
    },
  })
  const exportedText = await getText('/api/audit-export?format=jsonl&trace=trace-enterprise-audit', {
    authorization: `Bearer ${adminKey.token}`,
  })
  assert.ok(exportedText.includes('"audit_enterprise_export"'))
  assert.ok(!exportedText.includes('raw audit content'))

  await post('/api/events', {
    id: 'evt_enterprise_old',
    createdAt: '2026-01-01T00:00:00.000Z',
    traceId: 'trace-retention-old',
    tenant: 'acme',
    feature: 'enterprise-admin',
    metadata: { decision: { action: 'allow' }, content: 'old raw event' },
  })
  await post(
    '/api/retention-policies',
    {
      id: 'retention_enterprise_events_30d',
      resource: 'events',
      maxAgeDays: 30,
    },
    { authorization: `Bearer ${adminKey.token}` },
  )
  const dryRun = await post(
    '/api/retention/apply',
    { dryRun: true, now: '2026-07-12T00:00:00.000Z' },
    { authorization: `Bearer ${adminKey.token}` },
  )
  const dryRunEvents = dryRun.items.find((item) => item.policyId === 'retention_enterprise_events_30d')
  assert.deepEqual(dryRunEvents.expiredIds, ['evt_enterprise_old'])

  await post(
    '/api/retention/apply',
    { dryRun: false, now: '2026-07-12T00:00:00.000Z' },
    { authorization: `Bearer ${adminKey.token}` },
  )
  const retained = await get('/api/events?trace=trace-retention-old')
  assert.equal(retained.items.length, 0)
})

test('stores platform workflow releases with sanitized metadata', async () => {
  const release = await post('/api/workflow-releases', {
    id: 'workflow_release_support_300',
    workflowId: 'support-platform-release',
    releaseVersion: '3.0.0',
    status: 'blocked',
    policySource: 'project:prod-support',
    profileId: 'platform-prod-support',
    workflowHash: 'sha256:abc123',
    metadata: {
      owner: 'platform',
      content: 'raw release narrative must not persist',
      nested: {
        messages: [{ role: 'user', content: 'raw prompt must not persist' }],
        ticket: 'REL-300',
      },
    },
  })

  assert.equal(release.id, 'workflow_release_support_300')
  assert.deepEqual(release.metadata, { owner: 'platform', nested: { ticket: 'REL-300' } })

  const listed = await get('/api/workflow-releases?policySource=project:prod-support')
  assert.equal(listed.items.length, 1)
  assert.equal(listed.items[0].workflowHash, 'sha256:abc123')
  assert.deepEqual(listed.items[0].metadata, { owner: 'platform', nested: { ticket: 'REL-300' } })
})

test('serves control-plane UX v2 assets', async () => {
  const html = await getText('/')
  const app = await getText('/app.js')
  const css = await getText('/styles.css')

  assert.match(html, /Control Plane/)
  assert.match(html, /\/app\.js/)
  assert.match(app, /workflow-releases\/import/)
  assert.match(css, /\.shell/)
})

test('overview summarizes resources without content-bearing metadata', async () => {
  await post('/api/events', {
    id: 'evt_overview_001',
    kind: 'runtime.event',
    traceId: 'trace-overview-001',
    tenant: 'acme',
    feature: 'control-plane',
    model: 'gpt-4o-mini',
    provider: 'openai',
    risk: 'medium',
    policySource: 'project:overview',
    metadata: {
      decision: { action: 'allow' },
      content: 'raw overview prompt must not persist',
      nested: { messages: [{ role: 'user', content: 'raw overview message' }], ticket: 'OV-31' },
    },
  })

  const overview = await get('/api/overview')
  const serialized = JSON.stringify(overview)
  assert.equal(overview.schemaVersion, 'gavio.control-plane-overview.v1')
  assert.ok(overview.counts.events >= 1)
  assert.ok(Array.isArray(overview.activeRollouts))
  assert.ok(Array.isArray(overview.latestWorkflowReleases))
  assert.ok(serialized.includes('OV-31'))
  assert.ok(!serialized.includes('raw overview prompt'))
  assert.ok(!serialized.includes('raw overview message'))
})

test('demo seed is opt-in and creates usable metadata-safe records', async () => {
  const disabled = await fetch(`${base}/api/demo/seed`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: '{}',
  })
  assert.equal(disabled.status, 403)

  const dir = mkdtempSync(join(tmpdir(), 'gavio-control-plane-demo-'))
  const demo = await startControlPlane({ port: 0, statePath: join(dir, 'state.json'), demo: true })
  try {
    const seeded = await post('/api/demo/seed', {}, {}, demo.url)
    assert.equal(seeded.schemaVersion, 'gavio.control-plane-demo.v1')
    assert.match(seeded.runtimeToken, /^gav_rt_/)
    assert.equal(seeded.items.runtimeKey.token, undefined)

    const overview = await get('/api/overview', {}, demo.url)
    const serialized = JSON.stringify(overview)
    assert.equal(overview.demoEnabled, true)
    assert.equal(overview.counts.projects, 1)
    assert.equal(overview.counts.workflowReleases, 1)
    assert.ok(!serialized.includes('demo prompt content'))
    assert.ok(!serialized.includes('demo release metadata'))

    const config = await get(
      `/api/runtime/config?policy_source=${encodeURIComponent(seeded.policySource)}`,
      { authorization: `Bearer ${seeded.runtimeToken}` },
      demo.url,
    )
    assert.equal(config.projectId, seeded.items.project.id)
    assert.equal(config.policy.id, seeded.items.policy.id)
  } finally {
    await closeServer(demo.server)
  }
})

test('imports canonical workflow releases with filters and sanitized metadata', async () => {
  const imported = await post('/api/workflow-releases/import', {
    schemaVersion: 'gavio.platform-workflow-release.v1',
    workflowId: 'support-imported-release',
    generatedAt: '2026-07-16T12:00:00.000Z',
    release: { version: '3.1.0', tag: 'v3.1.0', commit: 'abc1234' },
    passed: false,
    reasons: ['eval:blocked'],
    prompts: { releaseBundles: [{ bundleId: 'support.reply', passed: true }] },
    evals: [{ suite: 'suite.json', passed: false }],
    policies: [{ id: 'core-policy', signatureValid: true }],
    trust: { valid: true, runtime: { policySource: 'project:imported-support' } },
    runtimeProfile: {
      profileId: 'platform-imported-support',
      valid: true,
      readiness: { ready: false },
    },
    metadata: {
      owner: 'platform',
      content: 'raw imported release note must not persist',
      nested: { diff: 'raw release diff', ticket: 'REL-31' },
    },
    workflowHash: 'sha256:imported31',
  })

  assert.equal(imported.workflowId, 'support-imported-release')
  assert.match(imported.id, /^workflow_/)
  assert.equal(imported.releaseVersion, '3.1.0')
  assert.equal(imported.status, 'blocked')
  assert.equal(imported.policySource, 'project:imported-support')
  assert.equal(imported.profileId, 'platform-imported-support')
  assert.deepEqual(imported.metadata, {
    owner: 'platform',
    nested: { ticket: 'REL-31' },
    importedSchemaVersion: 'gavio.platform-workflow-release.v1',
  })

  const listed = await get('/api/workflow-releases?policySource=project:imported-support&releaseVersion=3.1.0&status=blocked')
  const serialized = JSON.stringify(listed)
  assert.equal(listed.items.length, 1)
  assert.ok(!serialized.includes('raw imported release note'))
  assert.ok(!serialized.includes('raw release diff'))

  const invalid = await fetch(`${base}/api/workflow-releases/import`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ schemaVersion: 'wrong' }),
  })
  assert.equal(invalid.status, 400)
})

test('sqlite storage migrates and persists control-plane records across restarts', { skip: !hasNodeSqlite }, async () => {
  const dir = mkdtempSync(join(tmpdir(), 'gavio-control-plane-sqlite-'))
  const sqlitePath = join(dir, 'control-plane.sqlite')
  const first = await startControlPlane({ port: 0, storage: 'sqlite', sqlitePath })
  let runtimeKey
  try {
    assert.deepEqual(await first.store.migrationVersions(), [1])
    await post('/api/projects', { id: 'proj_durable', name: 'Durable Support' }, {}, first.url)
    await post('/api/environments', { id: 'env_durable_prod', projectId: 'proj_durable', name: 'prod' }, {}, first.url)
    await post(
      '/api/policies',
      {
        id: 'pol_durable',
        name: 'Durable policy',
        policyPack: 'support',
        rules: [{ id: 'durable-email', action: 'redact' }],
      },
      {},
      first.url,
    )
    await post(
      '/api/budgets',
      {
        id: 'budget_durable',
        projectId: 'proj_durable',
        scopeType: 'project',
        limitUsd: 42,
        window: 'day',
        action: 'warn',
      },
      {},
      first.url,
    )
    const key = await post(
      '/api/keys',
      {
        id: 'key_durable_prod',
        projectId: 'proj_durable',
        environment: 'prod',
        name: 'prod runtime',
      },
      {},
      first.url,
    )
    runtimeKey = key.token
    await post(
      '/api/policy-rollouts',
      {
        id: 'rollout_durable_prod',
        projectId: 'proj_durable',
        environment: 'prod',
        policySource: 'project:durable-support',
        policyId: 'pol_durable',
        cacheTtlSeconds: 180,
      },
      {},
      first.url,
    )
    await post(
      '/api/events',
      {
        id: 'evt_durable_001',
        traceId: 'trace-durable',
        tenant: 'acme',
        feature: 'support-chat',
        model: 'gpt-4o-mini',
        provider: 'openai',
        risk: 'medium',
        metadata: {
          decision: { action: 'allow' },
          messages: [{ role: 'user', content: 'raw prompt must not persist' }],
        },
      },
      { authorization: `Bearer ${runtimeKey}` },
      first.url,
    )
    await post(
      '/api/audit-records',
      {
        id: 'audit_durable_001',
        kind: 'admin.audit',
        actor: 'admin',
        action: 'review',
        resource: 'policies',
        resourceId: 'pol_durable',
        traceId: 'trace-audit',
        tenant: 'acme',
        feature: 'support-chat',
        metadata: {
          decision: { status: 'approved' },
          content: 'raw policy text must not persist',
        },
      },
      {},
      first.url,
    )
    await post(
      '/api/identity-providers',
      {
        id: 'idp_durable_saml',
        protocol: 'saml',
        issuer: 'https://idp.durable.example',
        privateKey: 'raw private key must not persist',
      },
      {},
      first.url,
    )
    await post(
      '/api/admin-keys',
      {
        id: 'adminkey_durable_audit',
        name: 'Durable audit key',
        scopes: ['audit:export'],
      },
      {},
      first.url,
    )
    await post(
      '/api/retention-policies',
      {
        id: 'retention_durable_audit_365d',
        resource: 'auditRecords',
        maxAgeDays: 365,
      },
      {},
      first.url,
    )
  } finally {
    await closeServer(first.server)
  }

  const restarted = await startControlPlane({ port: 0, storage: 'sqlite', sqlitePath })
  try {
    assert.deepEqual(await restarted.store.migrationVersions(), [1])

    const projects = await get('/api/projects', {}, restarted.url)
    assert.equal(projects.items[0].id, 'proj_durable')

    const listedKeys = await get('/api/keys', {}, restarted.url)
    assert.equal(listedKeys.items[0].id, 'key_durable_prod')
    assert.equal(listedKeys.items[0].keyHash, undefined)

    const config = await get(
      '/api/runtime/config?policy_source=project:durable-support',
      { authorization: `Bearer ${runtimeKey}` },
      restarted.url,
    )
    assert.equal(config.projectId, 'proj_durable')
    assert.equal(config.policy.id, 'pol_durable')
    assert.equal(config.budgets[0].limitUsd, 42)
    assert.equal(config.cache.ttlSeconds, 180)

    const snapshots = await get('/api/config-snapshots', {}, restarted.url)
    assert.equal(snapshots.items.length, 1)
    assert.equal(snapshots.items[0].policySource, 'project:durable-support')

    const events = await get(
      '/api/events?trace=trace-durable&tenant=acme&feature=support-chat&model=gpt-4o-mini&provider=openai&risk=medium',
      {},
      restarted.url,
    )
    assert.equal(events.items.length, 1)
    assert.deepEqual(events.items[0].metadata, { decision: { action: 'allow' } })

    const audit = await get('/api/audit-records?trace=trace-audit&tenant=acme&feature=support-chat', {}, restarted.url)
    assert.equal(audit.items.length, 1)
    assert.deepEqual(audit.items[0].metadata, { decision: { status: 'approved' } })

    const identityProviders = await get('/api/identity-providers', {}, restarted.url)
    assert.equal(identityProviders.items[0].id, 'idp_durable_saml')
    assert.equal(identityProviders.items[0].privateKey, undefined)
    assert.match(identityProviders.items[0].privateKeyHash, /^[a-f0-9]{64}$/)

    const adminKeys = await get('/api/admin-keys', {}, restarted.url)
    assert.equal(adminKeys.items[0].id, 'adminkey_durable_audit')
    assert.equal(adminKeys.items[0].keyHash, undefined)

    const retentionPolicies = await get('/api/retention-policies', {}, restarted.url)
    assert.equal(retentionPolicies.items[0].id, 'retention_durable_audit_365d')
  } finally {
    await closeServer(restarted.server)
  }
})

test('postgres storage reports actionable configuration and driver errors', async () => {
  await assert.rejects(
    () => startControlPlane({ port: 0, storage: 'postgres' }),
    /GAVIO_CONTROL_PLANE_DATABASE_URL/,
  )
  await assert.rejects(
    () => startControlPlane({ port: 0, storage: 'postgres', databaseUrl: 'postgres://localhost/gavio' }),
    /optional 'pg' package/,
  )
})

async function get(path, headers = {}, target = base) {
  const response = await fetch(`${target}${path}`, { headers })
  if (!response.ok) assert.fail(await response.text())
  return response.json()
}

async function post(path, body, headers = {}, target = base) {
  const response = await fetch(`${target}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  if (!response.ok) assert.fail(await response.text())
  return response.json()
}

async function getText(path, headers = {}, target = base) {
  const response = await fetch(`${target}${path}`, { headers })
  if (!response.ok) assert.fail(await response.text())
  return response.text()
}

function closeServer(target) {
  return new Promise((resolve, reject) => {
    target.close((error) => (error ? reject(error) : resolve()))
  })
}
