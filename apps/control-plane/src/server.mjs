import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ROLES, createStore } from './store.mjs'

const CONTENT_KEYS = new Set(['messages', 'content', 'diff'])
const STATIC_ASSETS = new Map([
  ['/app.js', 'application/javascript'],
  ['/styles.css', 'text/css'],
])
const API_RESOURCES = new Map([
  ['/api/projects', 'projects'],
  ['/api/environments', 'environments'],
  ['/api/teams', 'teams'],
  ['/api/identity-providers', 'identityProviders'],
  ['/api/policies', 'policies'],
  ['/api/policy-rollouts', 'policyRollouts'],
  ['/api/policy-approvals', 'policyApprovals'],
  ['/api/budgets', 'budgets'],
  ['/api/events', 'events'],
  ['/api/audit-records', 'auditRecords'],
  ['/api/config-snapshots', 'configSnapshots'],
  ['/api/retention-policies', 'retentionPolicies'],
  ['/api/workflow-releases', 'workflowReleases'],
])

export async function startControlPlane(options = {}) {
  const store = await createStore({
    storage: options.storage,
    statePath: options.statePath,
    sqlitePath: options.sqlitePath,
    databaseUrl: options.databaseUrl,
  })
  const context = {
    demoEnabled:
      Boolean(options.demoEnabled ?? options.demo) ||
      ['1', 'true', 'yes'].includes(String(process.env.GAVIO_CONTROL_PLANE_DEMO ?? '').toLowerCase()),
  }
  const host = options.host ?? '127.0.0.1'
  const port = options.port ?? 8787
  const server = createServer((req, res) => {
    handleRequest(req, res, store, context).catch((error) => sendError(res, error))
  })
  server.on('close', () => {
    void Promise.resolve(store.close?.()).catch(() => {})
  })
  await new Promise((resolve) => server.listen(port, host, resolve))
  const address = server.address()
  const url = `http://${host}:${address.port}`
  return { server, store, url }
}

async function handleRequest(req, res, store, context = {}) {
  const url = new URL(req.url, 'http://gavio.local')
  if (req.method === 'GET' && url.pathname === '/') {
    return sendHtml(res, readUi())
  }
  if (req.method === 'GET' && STATIC_ASSETS.has(url.pathname)) {
    return sendText(res, readPublic(url.pathname.slice(1)), STATIC_ASSETS.get(url.pathname))
  }
  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, { ok: true, service: 'gavio-control-plane', storage: store.kind })
  }
  if (req.method === 'GET' && url.pathname === '/api/overview') {
    return sendJson(res, await buildOverview(store, context))
  }
  if (req.method === 'GET' && url.pathname === '/api/runtime/config') {
    const policySource = url.searchParams.get('policy_source') ?? url.searchParams.get('policySource')
    const token = bearerToken(req)
    if (!policySource) return sendError(res, httpError(400, 'policy_source is required'))
    const config = await store.runtimeConfig(policySource, token, url.searchParams.get('fail_mode') ?? 'open')
    return sendJson(res, config)
  }
  if (req.method === 'GET' && url.pathname === '/api/audit-export') {
    await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'audit:export')
    const exportPayload = await store.auditExport(Object.fromEntries(url.searchParams))
    if (url.searchParams.get('format') === 'jsonl') {
      return sendText(res, `${exportPayload.items.map((item) => JSON.stringify(item)).join('\n')}\n`, 'application/x-ndjson')
    }
    return sendJson(res, exportPayload)
  }
  if (req.method === 'POST' && url.pathname === '/api/retention/apply') {
    const actor = await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'retention:write')
    return sendJson(res, await store.applyRetention(await readJson(req), actor))
  }
  if (req.method === 'POST' && url.pathname === '/api/demo/seed') {
    if (!context.demoEnabled) return sendError(res, httpError(403, 'demo seed is disabled'))
    const actor = await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'admin:write')
    return sendJson(res, await seedDemo(store, actor), 201)
  }
  if (req.method === 'POST' && url.pathname === '/api/workflow-releases/import') {
    const actor = await requireMutation(req, 'workflowReleases', store)
    const item = await store.create('workflowReleases', workflowReleaseRecordFromArtifact(await readJson(req)), actor)
    return sendJson(res, item, 201)
  }
  const approvalMatch = /^\/api\/policy-rollouts\/([^/]+)\/approvals$/.exec(url.pathname)
  if (approvalMatch) {
    if (req.method !== 'POST') return sendError(res, httpError(405, 'method not allowed'))
    const actor = await requireAdminScope(req, store, ROLES.WRITE_POLICY, 'policy:approve')
    return sendJson(res, await store.approvePolicyRollout(decodeURIComponent(approvalMatch[1]), await readJson(req), actor), 201)
  }
  if (url.pathname === '/api/keys') {
    if (req.method === 'GET') return sendJson(res, { items: await store.listKeys() })
    if (req.method === 'POST') {
      const actor = await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'admin:keys.write')
      return sendJson(res, await store.createRuntimeKey(await readJson(req), actor), 201)
    }
  }
  if (url.pathname === '/api/admin-keys') {
    if (req.method === 'GET') {
      await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'admin:keys.read')
      return sendJson(res, { items: await store.listAdminKeys() })
    }
    if (req.method === 'POST') {
      const actor = await requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'admin:keys.write')
      return sendJson(res, await store.createAdminKey(await readJson(req), actor), 201)
    }
  }
  const resource = API_RESOURCES.get(url.pathname)
  if (!resource) return sendError(res, httpError(404, 'not found'))
  if (req.method === 'GET') {
    return sendJson(res, { items: await store.list(resource, Object.fromEntries(url.searchParams)) })
  }
  if (req.method === 'POST') {
    const actor = await requireMutation(req, resource, store)
    const item = await store.create(resource, await readJson(req), actor)
    return sendJson(res, item, 201)
  }
  return sendError(res, httpError(405, 'method not allowed'))
}

async function buildOverview(store, context) {
  const [
    projects,
    environments,
    keys,
    adminKeys,
    identityProviders,
    policies,
    policyRollouts,
    policyApprovals,
    budgets,
    events,
    auditRecords,
    configSnapshots,
    retentionPolicies,
    workflowReleases,
  ] = await Promise.all([
    store.list('projects'),
    store.list('environments'),
    store.listKeys(),
    store.listAdminKeys(),
    store.list('identityProviders'),
    store.list('policies'),
    store.list('policyRollouts'),
    store.list('policyApprovals'),
    store.list('budgets'),
    store.list('events'),
    store.list('auditRecords'),
    store.list('configSnapshots'),
    store.list('retentionPolicies'),
    store.list('workflowReleases'),
  ])

  const counts = {
    projects: projects.length,
    environments: environments.length,
    keys: keys.length,
    adminKeys: adminKeys.length,
    identityProviders: identityProviders.length,
    policies: policies.length,
    policyRollouts: policyRollouts.length,
    policyApprovals: policyApprovals.length,
    budgets: budgets.length,
    events: events.length,
    auditRecords: auditRecords.length,
    configSnapshots: configSnapshots.length,
    retentionPolicies: retentionPolicies.length,
    workflowReleases: workflowReleases.length,
  }

  return {
    schemaVersion: 'gavio.control-plane-overview.v1',
    generatedAt: new Date().toISOString(),
    storage: store.kind,
    demoEnabled: Boolean(context.demoEnabled),
    counts,
    recentEvents: newest(events).slice(0, 10).map((item) => pick(item, eventFields())),
    recentAuditRecords: newest(auditRecords).slice(0, 10).map((item) => pick(item, auditFields())),
    activeRollouts: newest(policyRollouts.filter((item) => item.status === 'active')).slice(0, 10).map((item) =>
      pick(item, ['id', 'projectId', 'environment', 'policySource', 'policyId', 'status', 'percentage', 'updatedAt']),
    ),
    latestWorkflowReleases: newest(workflowReleases).slice(0, 10).map((item) =>
      pick(item, [
        'id',
        'workflowId',
        'releaseVersion',
        'status',
        'policySource',
        'profileId',
        'workflowHash',
        'generatedAt',
        'updatedAt',
        'metadata',
      ]),
    ),
    retentionPolicies: newest(retentionPolicies).slice(0, 5).map((item) =>
      pick(item, ['id', 'resource', 'maxAgeDays', 'status', 'updatedAt']),
    ),
  }
}

async function seedDemo(store, actor) {
  const suffix = Date.now().toString(36)
  const projectId = `proj_demo_${suffix}`
  const environment = 'prod'
  const policySource = `project:demo-support-${suffix}`
  const policyId = `pol_demo_support_${suffix}`
  const workflowId = `workflow_demo_support_${suffix}`

  const project = await store.create('projects', { id: projectId, name: 'Demo Support', owner: 'platform' }, actor)
  const environmentRecord = await store.create(
    'environments',
    { id: `env_demo_prod_${suffix}`, projectId, name: environment },
    actor,
  )
  const policy = await store.create(
    'policies',
    {
      id: policyId,
      name: 'Demo support policy',
      policyPack: 'support',
      rules: [{ id: 'support-pii-redact', action: 'redact' }],
    },
    actor,
  )
  const budget = await store.create(
    'budgets',
    {
      id: `budget_demo_support_${suffix}`,
      projectId,
      scopeType: 'project',
      limitUsd: 75,
      window: 'day',
      action: 'warn',
    },
    actor,
  )
  const runtimeKey = await store.createRuntimeKey(
    {
      id: `key_demo_support_${suffix}`,
      projectId,
      environment,
      name: 'Demo runtime key',
    },
    actor,
  )
  const rollout = await store.create(
    'policyRollouts',
    {
      id: `rollout_demo_support_${suffix}`,
      projectId,
      environment,
      policySource,
      policyId,
      percentage: 100,
      cacheTtlSeconds: 180,
    },
    actor,
  )
  const event = await store.create('events', {
    id: `evt_demo_runtime_${suffix}`,
    kind: 'runtime.event',
    traceId: `trace-demo-${suffix}`,
    tenant: 'demo',
    feature: 'support-chat',
    model: 'gpt-4o-mini',
    provider: 'openai',
    risk: 'medium',
    policySource,
    metadata: {
      decision: { action: 'allow' },
      latencyMs: 182,
      content: 'demo prompt content is removed before storage',
    },
  })
  const auditRecord = await store.create('auditRecords', {
    id: `audit_demo_review_${suffix}`,
    kind: 'admin.audit',
    actor,
    action: 'demo.seed',
    resource: 'policyRollouts',
    resourceId: rollout.id,
    traceId: event.traceId,
    tenant: 'demo',
    feature: 'control-plane',
    metadata: {
      ticket: 'DEMO-31',
      content: 'demo audit note is removed before storage',
    },
  })
  const workflowRelease = await store.create(
    'workflowReleases',
    {
      id: `workflow_release_demo_${suffix}`,
      workflowId,
      releaseVersion: '3.1.0-demo',
      status: 'passed',
      policySource,
      profileId: `platform_demo_${suffix}`,
      workflowHash: `sha256:demo${suffix}`,
      metadata: {
        owner: 'platform',
        messages: [{ role: 'user', content: 'demo release metadata is removed' }],
      },
    },
    actor,
  )
  const retentionPolicy = await store.create(
    'retentionPolicies',
    {
      id: `retention_demo_events_${suffix}`,
      resource: 'events',
      maxAgeDays: 30,
    },
    actor,
  )

  return {
    schemaVersion: 'gavio.control-plane-demo.v1',
    seededAt: new Date().toISOString(),
    policySource,
    runtimeToken: runtimeKey.token,
    items: {
      project,
      environment: environmentRecord,
      policy,
      budget,
      rollout,
      event,
      auditRecord,
      workflowRelease,
      retentionPolicy,
      runtimeKey: { ...runtimeKey, token: undefined },
    },
  }
}

function workflowReleaseRecordFromArtifact(input) {
  const artifact = input.artifact && typeof input.artifact === 'object' ? input.artifact : input
  if (artifact.schemaVersion !== 'gavio.platform-workflow-release.v1') {
    throw httpError(400, 'workflow release artifact schemaVersion must be gavio.platform-workflow-release.v1')
  }
  const release = objectOrEmpty(artifact.release)
  const runtimeProfile = objectOrEmpty(artifact.runtimeProfile)
  const runtimeProfileReadiness = objectOrEmpty(runtimeProfile.readiness)
  const trust = objectOrEmpty(artifact.trust)
  const passed = artifact.passed !== false
  const metadata = stripContentFields({
    ...objectOrEmpty(artifact.metadata),
    importedSchemaVersion: artifact.schemaVersion,
  })
  const record = {
    workflowId: artifact.workflowId,
    releaseVersion: release.version,
    releaseTag: release.tag,
    releaseCommit: release.commit,
    generatedAt: artifact.generatedAt,
    status: passed ? 'passed' : 'blocked',
    passed,
    reasons: Array.isArray(artifact.reasons) ? artifact.reasons : [],
    policySource:
      input.policySource ??
      artifact.policySource ??
      trust.runtime?.policySource ??
      runtimeProfile.runtime?.policySource ??
      artifact.metadata?.policySource,
    profileId: runtimeProfile.profileId ?? runtimeProfile.id ?? runtimeProfile.profile?.id,
    workflowHash: artifact.workflowHash,
    evidence: stripContentFields({
      promptBundleCount: arrayOrEmpty(artifact.prompts?.releaseBundles).length,
      evalCount: arrayOrEmpty(artifact.evals).length,
      policyCount: arrayOrEmpty(artifact.policies).length,
      trustValid: trust.valid,
      runtimeProfileValid: runtimeProfile.valid,
      runtimeProfileReady: runtimeProfileReadiness.ready,
    }),
    metadata,
  }
  if (input.id) record.id = input.id
  return record
}

async function requireMutation(req, resource, store) {
  if (resource === 'events') {
    const token = bearerToken(req, false)
    if (token) {
      if (!(await store.verifyRuntimeKey(token))) throw httpError(401, 'invalid runtime key')
      return 'runtime'
    }
    return requireRole(req, ROLES.WRITE_EVENT)
  }
  if (resource === 'policies' || resource === 'policyRollouts') {
    return requireAdminScope(req, store, ROLES.WRITE_POLICY, 'policy:write')
  }
  if (resource === 'identityProviders') return requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'identity:write')
  if (resource === 'retentionPolicies') return requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'retention:write')
  if (resource === 'policyApprovals') return requireAdminScope(req, store, ROLES.WRITE_POLICY, 'policy:approve')
  return requireAdminScope(req, store, ROLES.WRITE_ADMIN, 'admin:write')
}

function role(req) {
  return (req.headers['x-gavio-role'] ?? 'owner').toString()
}

function requireRole(req, allowed) {
  const value = role(req)
  if (!allowed.has(value)) throw httpError(403, `role ${value} cannot perform this action`)
  return value
}

async function requireAdminScope(req, store, allowedRoles, scope) {
  const token = bearerToken(req, false)
  if (token) {
    const key = await store.verifyAdminKey(token, scope)
    if (!key) throw httpError(403, `admin key cannot perform ${scope}`)
    return `admin-key:${key.id}`
  }
  return requireRole(req, allowedRoles)
}

function bearerToken(req, required = true) {
  const header = req.headers.authorization ?? ''
  const match = /^Bearer\s+(.+)$/i.exec(String(header))
  if (!match && required) throw httpError(401, 'missing bearer runtime key')
  return match?.[1] ?? ''
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = ''
    req.setEncoding('utf8')
    req.on('data', (chunk) => {
      body += chunk
      if (body.length > 1_000_000) reject(httpError(413, 'request too large'))
    })
    req.on('end', () => {
      if (!body.trim()) return resolve({})
      try {
        resolve(JSON.parse(body))
      } catch {
        reject(httpError(400, 'invalid JSON body'))
      }
    })
  })
}

function sendJson(res, payload, status = 200) {
  const body = `${JSON.stringify(payload, null, 2)}\n`
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function sendText(res, body, contentType, status = 200) {
  res.writeHead(status, {
    'content-type': `${contentType}; charset=utf-8`,
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function sendHtml(res, html) {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
  res.end(html)
}

function sendError(res, error) {
  sendJson(res, { error: error.message ?? 'internal error' }, error.status ?? 500)
}

function httpError(status, message) {
  const error = new Error(message)
  error.status = status
  return error
}

function readUi() {
  const here = dirname(fileURLToPath(import.meta.url))
  return readFileSync(join(here, '../public/index.html'), 'utf8')
}

function readPublic(fileName) {
  const here = dirname(fileURLToPath(import.meta.url))
  return readFileSync(join(here, '../public', fileName), 'utf8')
}

function newest(items) {
  return [...items].sort((left, right) => {
    const leftTime = Date.parse(left.updatedAt ?? left.createdAt ?? left.generatedAt ?? 0)
    const rightTime = Date.parse(right.updatedAt ?? right.createdAt ?? right.generatedAt ?? 0)
    return rightTime - leftTime
  })
}

function eventFields() {
  return ['id', 'kind', 'traceId', 'tenant', 'feature', 'model', 'provider', 'risk', 'policySource', 'createdAt', 'metadata']
}

function auditFields() {
  return ['id', 'kind', 'actor', 'action', 'resource', 'resourceId', 'traceId', 'tenant', 'feature', 'createdAt', 'metadata']
}

function pick(record, fields) {
  const out = {}
  for (const field of fields) {
    if (record[field] !== undefined) out[field] = stripContentFields(record[field])
  }
  return out
}

function stripContentFields(value) {
  if (Array.isArray(value)) return value.map(stripContentFields).filter((item) => item !== undefined)
  if (!value || typeof value !== 'object') return value
  const out = {}
  for (const [key, nested] of Object.entries(value)) {
    if (CONTENT_KEYS.has(key)) continue
    const cleaned = stripContentFields(nested)
    if (cleaned !== undefined) out[key] = cleaned
  }
  return out
}

function objectOrEmpty(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

function arrayOrEmpty(value) {
  return Array.isArray(value) ? value : []
}
