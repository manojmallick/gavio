import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ROLES, createStore } from './store.mjs'

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
])

export async function startControlPlane(options = {}) {
  const store = await createStore({
    storage: options.storage,
    statePath: options.statePath,
    sqlitePath: options.sqlitePath,
    databaseUrl: options.databaseUrl,
  })
  const host = options.host ?? '127.0.0.1'
  const port = options.port ?? 8787
  const server = createServer((req, res) => {
    handleRequest(req, res, store).catch((error) => sendError(res, error))
  })
  server.on('close', () => {
    void Promise.resolve(store.close?.()).catch(() => {})
  })
  await new Promise((resolve) => server.listen(port, host, resolve))
  const address = server.address()
  const url = `http://${host}:${address.port}`
  return { server, store, url }
}

async function handleRequest(req, res, store) {
  const url = new URL(req.url, 'http://gavio.local')
  if (req.method === 'GET' && url.pathname === '/') {
    return sendHtml(res, readUi())
  }
  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, { ok: true, service: 'gavio-control-plane', storage: store.kind })
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
