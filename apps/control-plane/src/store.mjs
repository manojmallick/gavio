import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const CONTENT_KEYS = new Set(['messages', 'content', 'diff'])

export const ROLES = Object.freeze({
  WRITE_POLICY: new Set(['owner', 'admin']),
  WRITE_ADMIN: new Set(['owner', 'admin']),
  WRITE_EVENT: new Set(['owner', 'admin', 'developer']),
})

export function createStore(options = {}) {
  const statePath = resolve(options.statePath ?? '.gavio-control-plane/state.json')
  const store = new ControlPlaneStore(statePath)
  store.load()
  return store
}

export function hashRuntimeKey(token) {
  return createHash('sha256').update(String(token), 'utf8').digest('hex')
}

export function sanitizeMetadata(value) {
  if (Array.isArray(value)) return value.map((item) => sanitizeMetadata(item))
  if (value === null || typeof value !== 'object') return value
  const out = {}
  for (const [key, item] of Object.entries(value)) {
    if (CONTENT_KEYS.has(key)) continue
    out[key] = sanitizeMetadata(item)
  }
  return out
}

class ControlPlaneStore {
  constructor(statePath) {
    this.statePath = statePath
    this.state = emptyState()
  }

  load() {
    try {
      this.state = normalizeState(JSON.parse(readFileSync(this.statePath, 'utf8')))
    } catch {
      this.state = emptyState()
      this.save()
    }
  }

  save() {
    mkdirSync(dirname(this.statePath), { recursive: true })
    writeFileSync(this.statePath, `${JSON.stringify(this.state, null, 2)}\n`, 'utf8')
  }

  list(resource, filters = {}) {
    return searchRecords(this.state[resource] ?? [], filters)
  }

  create(resource, input, actor = 'owner') {
    const item = withDefaults(resource, input)
    if (resource === 'events' || resource === 'auditRecords') {
      item.metadata = sanitizeMetadata(item.metadata ?? {})
    }
    this.state[resource].push(item)
    if (isAdminResource(resource)) {
      this.appendAdminAudit(actor, 'create', resource, item.id)
    }
    this.save()
    return item
  }

  createRuntimeKey(input, actor = 'owner') {
    const token = `gav_rt_${randomUUID().replaceAll('-', '')}`
    const item = withDefaults('keys', {
      ...input,
      prefix: token.slice(0, 14),
      keyHash: hashRuntimeKey(token),
    })
    this.state.keys.push(item)
    this.appendAdminAudit(actor, 'create', 'keys', item.id)
    this.save()
    return { ...redactKey(item), token }
  }

  listKeys() {
    return this.state.keys.map(redactKey)
  }

  verifyRuntimeKey(token) {
    const keyHash = hashRuntimeKey(token)
    return this.state.keys.find((item) => item.keyHash === keyHash && item.status !== 'revoked')
  }

  runtimeConfig(policySource, token, failMode = 'open') {
    const runtimeKey = this.verifyRuntimeKey(token)
    if (!runtimeKey) {
      const error = new Error('invalid runtime key')
      error.status = 401
      throw error
    }
    const rollout = this.state.policyRollouts.find(
      (item) => item.policySource === policySource && item.status === 'active',
    )
    if (!rollout) {
      const error = new Error(`no active rollout for ${policySource}`)
      error.status = 404
      throw error
    }
    if (rollout.projectId !== runtimeKey.projectId) {
      const error = new Error('runtime key is not scoped to this project')
      error.status = 403
      throw error
    }
    const policy = this.state.policies.find((item) => item.id === rollout.policyId)
    if (!policy) {
      const error = new Error(`policy ${rollout.policyId} not found`)
      error.status = 404
      throw error
    }
    const budgets = this.state.budgets.filter((budget) =>
      matchesBudgetScope(budget, rollout.projectId, rollout.environment),
    )
    const config = {
      schemaVersion: '1.0',
      configVersion: nextVersion('cfg'),
      projectId: rollout.projectId,
      environment: rollout.environment,
      policySource,
      policy,
      budgets,
      rollout: {
        id: rollout.id,
        policyId: rollout.policyId,
        status: rollout.status,
        percentage: rollout.percentage ?? 100,
      },
      cache: {
        ttlSeconds: Number(rollout.cacheTtlSeconds ?? 300),
        failMode,
        loadedFrom: 'control_plane',
      },
    }
    this.createSnapshot(config, 'runtime')
    return config
  }

  createSnapshot(config, actor = 'owner') {
    const snapshot = withDefaults('configSnapshots', {
      projectId: config.projectId,
      environment: config.environment,
      policySource: config.policySource,
      configVersion: config.configVersion,
      config,
    })
    this.state.configSnapshots.push(snapshot)
    if (actor !== 'runtime') {
      this.appendAdminAudit(actor, 'create', 'configSnapshots', snapshot.id)
    }
    this.save()
    return snapshot
  }

  appendAdminAudit(actor, action, resource, resourceId) {
    this.state.auditRecords.push(
      withDefaults('auditRecords', {
        kind: 'admin.audit',
        actor,
        action,
        resource,
        resourceId,
        metadata: {},
      }),
    )
  }
}

function emptyState() {
  return {
    projects: [],
    environments: [],
    keys: [],
    teams: [],
    policies: [],
    policyRollouts: [],
    budgets: [],
    events: [],
    auditRecords: [],
    configSnapshots: [],
  }
}

function normalizeState(value) {
  const state = emptyState()
  if (value && typeof value === 'object') {
    for (const key of Object.keys(state)) {
      if (Array.isArray(value[key])) state[key] = value[key]
    }
  }
  return state
}

function withDefaults(resource, input) {
  const now = new Date().toISOString()
  const idPrefix = {
    projects: 'proj',
    environments: 'env',
    keys: 'key',
    teams: 'team',
    policies: 'pol',
    policyRollouts: 'rollout',
    budgets: 'budget',
    events: 'evt',
    auditRecords: 'audit',
    configSnapshots: 'snap',
  }[resource]
  const item = {
    id: input.id ?? nextVersion(idPrefix),
    createdAt: input.createdAt ?? now,
    updatedAt: input.updatedAt ?? now,
    ...input,
  }
  if (resource === 'keys') item.status = item.status ?? 'active'
  if (resource === 'policyRollouts') item.status = item.status ?? 'active'
  if (resource === 'events') item.kind = item.kind ?? 'runtime.event'
  if (resource === 'auditRecords') item.kind = item.kind ?? 'admin.audit'
  return item
}

function nextVersion(prefix) {
  return `${prefix}_${randomUUID().slice(0, 8)}`
}

function redactKey(item) {
  const { keyHash, ...safe } = item
  return safe
}

function isAdminResource(resource) {
  return !['events', 'auditRecords', 'configSnapshots'].includes(resource)
}

function matchesBudgetScope(budget, projectId, environment) {
  if (budget.scopeType === 'project') return budget.projectId === projectId || budget.scopeId === projectId
  if (budget.scopeType === 'environment') {
    return (
      (budget.projectId === projectId || budget.projectId === undefined) &&
      (budget.environment === environment || budget.scopeId === environment)
    )
  }
  return true
}

function searchRecords(records, filters) {
  const start = filters.start ? Date.parse(filters.start) : null
  const end = filters.end ? Date.parse(filters.end) : null
  return records.filter((record) => {
    if (!matches(record.traceId, filters.trace ?? filters.traceId)) return false
    if (!matches(record.tenant, filters.tenant)) return false
    if (!matches(record.feature, filters.feature)) return false
    if (!matches(record.model, filters.model)) return false
    if (!matches(record.provider, filters.provider)) return false
    if (!matches(record.risk, filters.risk)) return false
    if (start !== null && Date.parse(record.createdAt) < start) return false
    if (end !== null && Date.parse(record.createdAt) > end) return false
    return true
  })
}

function matches(actual, expected) {
  if (expected === undefined || expected === null || expected === '') return true
  return String(actual ?? '') === String(expected)
}
