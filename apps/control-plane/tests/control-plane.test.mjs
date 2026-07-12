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

function closeServer(target) {
  return new Promise((resolve, reject) => {
    target.close((error) => (error ? reject(error) : resolve()))
  })
}
