import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const CONTENT_KEYS = new Set(['messages', 'content', 'diff'])
const SENSITIVE_IDENTITY_KEYS = new Set(['clientSecret', 'client_secret', 'privateKey', 'private_key', 'signingSecret'])
const RETAINED_RESOURCES = Object.freeze(['events', 'auditRecords', 'configSnapshots'])

const RESOURCE_NAMES = Object.freeze([
  'projects',
  'environments',
  'keys',
  'adminKeys',
  'teams',
  'identityProviders',
  'policies',
  'policyRollouts',
  'policyApprovals',
  'budgets',
  'events',
  'auditRecords',
  'configSnapshots',
  'retentionPolicies',
])

const MIGRATIONS = Object.freeze([
  {
    version: 1,
    name: 'record-store-v1',
    sqlite: [
      `CREATE TABLE IF NOT EXISTS gavio_control_plane_records (
        resource TEXT NOT NULL,
        id TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        project_id TEXT,
        environment TEXT,
        policy_source TEXT,
        status TEXT,
        trace_id TEXT,
        tenant TEXT,
        feature TEXT,
        model TEXT,
        provider TEXT,
        risk TEXT,
        key_hash TEXT,
        document TEXT NOT NULL,
        PRIMARY KEY (resource, id)
      )`,
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_resource_created ON gavio_control_plane_records(resource, created_at)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_runtime_key ON gavio_control_plane_records(resource, key_hash, status)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_policy_source ON gavio_control_plane_records(resource, policy_source, status)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_search ON gavio_control_plane_records(resource, trace_id, tenant, feature, model, provider, risk)',
    ],
    postgres: [
      `CREATE TABLE IF NOT EXISTS gavio_control_plane_records (
        resource TEXT NOT NULL,
        id TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        project_id TEXT,
        environment TEXT,
        policy_source TEXT,
        status TEXT,
        trace_id TEXT,
        tenant TEXT,
        feature TEXT,
        model TEXT,
        provider TEXT,
        risk TEXT,
        key_hash TEXT,
        document TEXT NOT NULL,
        PRIMARY KEY (resource, id)
      )`,
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_resource_created ON gavio_control_plane_records(resource, created_at)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_runtime_key ON gavio_control_plane_records(resource, key_hash, status)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_policy_source ON gavio_control_plane_records(resource, policy_source, status)',
      'CREATE INDEX IF NOT EXISTS idx_gavio_cp_search ON gavio_control_plane_records(resource, trace_id, tenant, feature, model, provider, risk)',
    ],
  },
])

export const ROLES = Object.freeze({
  WRITE_POLICY: new Set(['owner', 'admin']),
  WRITE_ADMIN: new Set(['owner', 'admin']),
  WRITE_EVENT: new Set(['owner', 'admin', 'developer']),
})

export class ControlPlanePersistenceError extends Error {
  name = 'ControlPlanePersistenceError'
}

export async function createStore(options = {}) {
  const storage = normalizeStorageMode(resolveStorageMode(options))
  if (storage === 'file') {
    const statePath = resolve(
      options.statePath ?? process.env.GAVIO_CONTROL_PLANE_STATE ?? '.gavio-control-plane/state.json',
    )
    const store = new JsonFileControlPlaneStore(statePath)
    await store.load()
    return store
  }
  if (storage === 'sqlite') {
    const sqlitePath = resolve(
      options.sqlitePath ?? process.env.GAVIO_CONTROL_PLANE_SQLITE_PATH ?? '.gavio-control-plane/control-plane.sqlite',
    )
    const adapter = await createSqliteAdapter(sqlitePath)
    const store = new DurableControlPlaneStore(adapter)
    await store.load()
    return store
  }
  if (storage === 'postgres') {
    const adapter = await createPostgresAdapter(options.databaseUrl ?? process.env.GAVIO_CONTROL_PLANE_DATABASE_URL)
    const store = new DurableControlPlaneStore(adapter)
    await store.load()
    return store
  }
  throw new ControlPlanePersistenceError(`unsupported control-plane storage mode: ${storage}`)
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

class JsonFileControlPlaneStore {
  constructor(statePath) {
    this.kind = 'file'
    this.statePath = statePath
    this.state = emptyState()
  }

  async load() {
    try {
      this.state = normalizeState(JSON.parse(readFileSync(this.statePath, 'utf8')))
    } catch {
      this.state = emptyState()
      await this.save()
    }
  }

  async save() {
    mkdirSync(dirname(this.statePath), { recursive: true })
    writeFileSync(this.statePath, `${JSON.stringify(this.state, null, 2)}\n`, 'utf8')
  }

  async list(resource, filters = {}) {
    return searchRecords(this.state[resource] ?? [], filters)
  }

  async create(resource, input, actor = 'owner') {
    let item = withDefaults(resource, input)
    if (resource === 'events' || resource === 'auditRecords' || resource === 'policyApprovals') {
      item.metadata = sanitizeMetadata(item.metadata ?? {})
    }
    if (resource === 'identityProviders') item = sanitizeIdentityProvider(item)
    this.state[resource].push(item)
    if (isAdminResource(resource)) {
      await this.appendAdminAudit(actor, 'create', resource, item.id)
    }
    await this.save()
    return item
  }

  async createRuntimeKey(input, actor = 'owner') {
    const token = `gav_rt_${randomUUID().replaceAll('-', '')}`
    const item = withDefaults('keys', {
      ...input,
      prefix: token.slice(0, 14),
      keyHash: hashRuntimeKey(token),
    })
    this.state.keys.push(item)
    await this.appendAdminAudit(actor, 'create', 'keys', item.id)
    await this.save()
    return { ...redactKey(item), token }
  }

  async createAdminKey(input, actor = 'owner') {
    const token = `gav_admin_${randomUUID().replaceAll('-', '')}`
    const item = withDefaults('adminKeys', {
      ...input,
      prefix: token.slice(0, 18),
      keyHash: hashRuntimeKey(token),
    })
    this.state.adminKeys.push(item)
    await this.appendAdminAudit(actor, 'create', 'adminKeys', item.id)
    await this.save()
    return { ...redactKey(item), token }
  }

  async listKeys() {
    return this.state.keys.map(redactKey)
  }

  async listAdminKeys() {
    return this.state.adminKeys.map(redactKey)
  }

  async verifyRuntimeKey(token) {
    const keyHash = hashRuntimeKey(token)
    return this.state.keys.find((item) => item.keyHash === keyHash && item.status !== 'revoked')
  }

  async verifyAdminKey(token, scope) {
    return verifyScopedAdminKey(this.state.adminKeys, token, scope)
  }

  async runtimeConfig(policySource, token, failMode = 'open') {
    return buildRuntimeConfig(this, policySource, token, failMode)
  }

  async createSnapshot(config, actor = 'owner') {
    const snapshot = withDefaults('configSnapshots', {
      projectId: config.projectId,
      environment: config.environment,
      policySource: config.policySource,
      configVersion: config.configVersion,
      config,
    })
    this.state.configSnapshots.push(snapshot)
    if (actor !== 'runtime') {
      await this.appendAdminAudit(actor, 'create', 'configSnapshots', snapshot.id)
    }
    await this.save()
    return snapshot
  }

  async approvePolicyRollout(rolloutId, input = {}, actor = 'owner') {
    const rollout = this.state.policyRollouts.find((item) => item.id === rolloutId)
    if (!rollout) throw httpError(404, `policy rollout ${rolloutId} not found`)
    const approval = withDefaults('policyApprovals', {
      rolloutId,
      actor: input.actor ?? actor,
      decision: input.decision ?? 'approved',
      metadata: sanitizeMetadata(input.metadata ?? {}),
    })
    this.state.policyApprovals.push(approval)
    const approvals = this.state.policyApprovals.filter((item) => item.rolloutId === rolloutId)
    const updated = applyRolloutApprovalState(rollout, approvals)
    Object.assign(rollout, updated)
    await this.appendAdminAudit(actor, 'approve', 'policyRollouts', rolloutId)
    await this.save()
    return { approval, rollout: updated }
  }

  async auditExport(filters = {}) {
    return buildAuditExport(this.state.auditRecords, filters)
  }

  async applyRetention(input = {}, actor = 'owner') {
    const result = applyRetentionToState(this.state, input)
    await this.appendAdminAudit(actor, result.dryRun ? 'retention.evaluate' : 'retention.apply', 'retentionPolicies', 'active')
    await this.save()
    return result
  }

  async appendAdminAudit(actor, action, resource, resourceId) {
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

  async migrationVersions() {
    return []
  }

  async close() {}
}

class DurableControlPlaneStore {
  constructor(adapter) {
    this.kind = adapter.kind
    this.adapter = adapter
  }

  async load() {
    await this.adapter.migrate(MIGRATIONS)
  }

  async list(resource, filters = {}) {
    return searchRecords(await this.adapter.listRecords(resource), filters)
  }

  async create(resource, input, actor = 'owner') {
    let item = withDefaults(resource, input)
    if (resource === 'events' || resource === 'auditRecords' || resource === 'policyApprovals') {
      item.metadata = sanitizeMetadata(item.metadata ?? {})
    }
    if (resource === 'identityProviders') item = sanitizeIdentityProvider(item)
    await this.adapter.upsertRecord(resource, item)
    if (isAdminResource(resource)) {
      await this.appendAdminAudit(actor, 'create', resource, item.id)
    }
    return item
  }

  async createRuntimeKey(input, actor = 'owner') {
    const token = `gav_rt_${randomUUID().replaceAll('-', '')}`
    const item = withDefaults('keys', {
      ...input,
      prefix: token.slice(0, 14),
      keyHash: hashRuntimeKey(token),
    })
    await this.adapter.upsertRecord('keys', item)
    await this.appendAdminAudit(actor, 'create', 'keys', item.id)
    return { ...redactKey(item), token }
  }

  async createAdminKey(input, actor = 'owner') {
    const token = `gav_admin_${randomUUID().replaceAll('-', '')}`
    const item = withDefaults('adminKeys', {
      ...input,
      prefix: token.slice(0, 18),
      keyHash: hashRuntimeKey(token),
    })
    await this.adapter.upsertRecord('adminKeys', item)
    await this.appendAdminAudit(actor, 'create', 'adminKeys', item.id)
    return { ...redactKey(item), token }
  }

  async listKeys() {
    return (await this.adapter.listRecords('keys')).map(redactKey)
  }

  async listAdminKeys() {
    return (await this.adapter.listRecords('adminKeys')).map(redactKey)
  }

  async verifyRuntimeKey(token) {
    const keyHash = hashRuntimeKey(token)
    return (await this.adapter.listRecords('keys')).find(
      (item) => item.keyHash === keyHash && item.status !== 'revoked',
    )
  }

  async verifyAdminKey(token, scope) {
    return verifyScopedAdminKey(await this.adapter.listRecords('adminKeys'), token, scope)
  }

  async runtimeConfig(policySource, token, failMode = 'open') {
    return buildRuntimeConfig(this, policySource, token, failMode)
  }

  async createSnapshot(config, actor = 'owner') {
    const snapshot = withDefaults('configSnapshots', {
      projectId: config.projectId,
      environment: config.environment,
      policySource: config.policySource,
      configVersion: config.configVersion,
      config,
    })
    await this.adapter.upsertRecord('configSnapshots', snapshot)
    if (actor !== 'runtime') {
      await this.appendAdminAudit(actor, 'create', 'configSnapshots', snapshot.id)
    }
    return snapshot
  }

  async approvePolicyRollout(rolloutId, input = {}, actor = 'owner') {
    const rollout = (await this.adapter.listRecords('policyRollouts')).find((item) => item.id === rolloutId)
    if (!rollout) throw httpError(404, `policy rollout ${rolloutId} not found`)
    const approval = withDefaults('policyApprovals', {
      rolloutId,
      actor: input.actor ?? actor,
      decision: input.decision ?? 'approved',
      metadata: sanitizeMetadata(input.metadata ?? {}),
    })
    await this.adapter.upsertRecord('policyApprovals', approval)
    const approvals = [...(await this.adapter.listRecords('policyApprovals')), approval].filter(
      (item, index, items) => item.rolloutId === rolloutId && items.findIndex((candidate) => candidate.id === item.id) === index,
    )
    const updated = applyRolloutApprovalState(rollout, approvals)
    await this.adapter.upsertRecord('policyRollouts', updated)
    await this.appendAdminAudit(actor, 'approve', 'policyRollouts', rolloutId)
    return { approval, rollout: updated }
  }

  async auditExport(filters = {}) {
    return buildAuditExport(await this.adapter.listRecords('auditRecords'), filters)
  }

  async applyRetention(input = {}, actor = 'owner') {
    const state = emptyState()
    for (const resource of ['retentionPolicies', ...RETAINED_RESOURCES]) {
      state[resource] = await this.adapter.listRecords(resource)
    }
    const result = applyRetentionToState(state, input)
    if (!result.dryRun) {
      for (const item of result.items) {
        if (!item.applied) continue
        for (const id of item.expiredIds) await this.adapter.deleteRecord(item.resource, id)
      }
    }
    await this.appendAdminAudit(actor, result.dryRun ? 'retention.evaluate' : 'retention.apply', 'retentionPolicies', 'active')
    return result
  }

  async appendAdminAudit(actor, action, resource, resourceId) {
    await this.adapter.upsertRecord(
      'auditRecords',
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

  async migrationVersions() {
    return this.adapter.migrationVersions()
  }

  async close() {
    await this.adapter.close?.()
  }
}

class SqliteAdapter {
  constructor(path, database) {
    this.kind = 'sqlite'
    this.path = path
    this.database = database
  }

  async migrate(migrations) {
    this.database.exec(`
      CREATE TABLE IF NOT EXISTS gavio_control_plane_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT NOT NULL,
        applied_at TEXT NOT NULL
      )
    `)
    for (const migration of migrations) {
      const applied = this.database
        .prepare('SELECT version FROM gavio_control_plane_migrations WHERE version = ?')
        .get(migration.version)
      if (applied) continue
      this.database.exec('BEGIN')
      try {
        for (const sql of migration.sqlite) this.database.exec(sql)
        this.database
          .prepare('INSERT INTO gavio_control_plane_migrations(version, name, applied_at) VALUES (?, ?, ?)')
          .run(migration.version, migration.name, new Date().toISOString())
        this.database.exec('COMMIT')
      } catch (error) {
        this.database.exec('ROLLBACK')
        throw error
      }
    }
  }

  async upsertRecord(resource, item) {
    this.database
      .prepare(
        `INSERT INTO gavio_control_plane_records (
          resource, id, created_at, updated_at, project_id, environment,
          policy_source, status, trace_id, tenant, feature, model, provider,
          risk, key_hash, document
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(resource, id) DO UPDATE SET
          updated_at = excluded.updated_at,
          project_id = excluded.project_id,
          environment = excluded.environment,
          policy_source = excluded.policy_source,
          status = excluded.status,
          trace_id = excluded.trace_id,
          tenant = excluded.tenant,
          feature = excluded.feature,
          model = excluded.model,
          provider = excluded.provider,
          risk = excluded.risk,
          key_hash = excluded.key_hash,
          document = excluded.document`,
      )
      .run(...recordParams(resource, item))
  }

  async listRecords(resource) {
    const rows = this.database
      .prepare('SELECT document FROM gavio_control_plane_records WHERE resource = ? ORDER BY created_at ASC, id ASC')
      .all(resource)
    return rows.map((row) => JSON.parse(row.document))
  }

  async deleteRecord(resource, id) {
    this.database.prepare('DELETE FROM gavio_control_plane_records WHERE resource = ? AND id = ?').run(resource, id)
  }

  async migrationVersions() {
    return this.database
      .prepare('SELECT version FROM gavio_control_plane_migrations ORDER BY version ASC')
      .all()
      .map((row) => row.version)
  }

  async close() {
    this.database.close()
  }
}

class PostgresAdapter {
  constructor(client) {
    this.kind = 'postgres'
    this.client = client
  }

  async migrate(migrations) {
    await this.client.query(`
      CREATE TABLE IF NOT EXISTS gavio_control_plane_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT NOT NULL,
        applied_at TEXT NOT NULL
      )
    `)
    for (const migration of migrations) {
      const applied = await this.client.query(
        'SELECT version FROM gavio_control_plane_migrations WHERE version = $1',
        [migration.version],
      )
      if (applied.rowCount > 0) continue
      await this.client.query('BEGIN')
      try {
        for (const sql of migration.postgres) await this.client.query(sql)
        await this.client.query(
          'INSERT INTO gavio_control_plane_migrations(version, name, applied_at) VALUES ($1, $2, $3) ON CONFLICT(version) DO NOTHING',
          [migration.version, migration.name, new Date().toISOString()],
        )
        await this.client.query('COMMIT')
      } catch (error) {
        await this.client.query('ROLLBACK')
        throw error
      }
    }
  }

  async upsertRecord(resource, item) {
    await this.client.query(
      `INSERT INTO gavio_control_plane_records (
        resource, id, created_at, updated_at, project_id, environment,
        policy_source, status, trace_id, tenant, feature, model, provider,
        risk, key_hash, document
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
      ON CONFLICT(resource, id) DO UPDATE SET
        updated_at = excluded.updated_at,
        project_id = excluded.project_id,
        environment = excluded.environment,
        policy_source = excluded.policy_source,
        status = excluded.status,
        trace_id = excluded.trace_id,
        tenant = excluded.tenant,
        feature = excluded.feature,
        model = excluded.model,
        provider = excluded.provider,
        risk = excluded.risk,
        key_hash = excluded.key_hash,
        document = excluded.document`,
      recordParams(resource, item),
    )
  }

  async listRecords(resource) {
    const result = await this.client.query(
      'SELECT document FROM gavio_control_plane_records WHERE resource = $1 ORDER BY created_at ASC, id ASC',
      [resource],
    )
    return result.rows.map((row) => (typeof row.document === 'string' ? JSON.parse(row.document) : row.document))
  }

  async deleteRecord(resource, id) {
    await this.client.query('DELETE FROM gavio_control_plane_records WHERE resource = $1 AND id = $2', [resource, id])
  }

  async migrationVersions() {
    const result = await this.client.query(
      'SELECT version FROM gavio_control_plane_migrations ORDER BY version ASC',
    )
    return result.rows.map((row) => row.version)
  }

  async close() {
    await this.client.end()
  }
}

async function buildRuntimeConfig(store, policySource, token, failMode = 'open') {
  const runtimeKey = await store.verifyRuntimeKey(token)
  if (!runtimeKey) {
    const error = new Error('invalid runtime key')
    error.status = 401
    throw error
  }
  const rollout = (await store.list('policyRollouts')).find(
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
  const policy = (await store.list('policies')).find((item) => item.id === rollout.policyId)
  if (!policy) {
    const error = new Error(`policy ${rollout.policyId} not found`)
    error.status = 404
    throw error
  }
  const budgets = (await store.list('budgets')).filter((budget) =>
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
  await store.createSnapshot(config, 'runtime')
  return config
}

async function createSqliteAdapter(path) {
  mkdirSync(dirname(path), { recursive: true })
  let sqlite
  try {
    sqlite = await import('node:sqlite')
  } catch (error) {
    throw new ControlPlanePersistenceError(
      `SQLite control-plane storage requires a Node.js runtime with node:sqlite support; ` +
        `use GAVIO_CONTROL_PLANE_STORAGE=file or postgres instead (${error.code ?? error.message})`,
    )
  }
  return new SqliteAdapter(path, new sqlite.DatabaseSync(path))
}

async function createPostgresAdapter(databaseUrl) {
  if (!databaseUrl) {
    throw new ControlPlanePersistenceError(
      'Postgres control-plane storage requires GAVIO_CONTROL_PLANE_DATABASE_URL or startControlPlane({ databaseUrl })',
    )
  }
  let pg
  try {
    pg = await import('pg')
  } catch {
    throw new ControlPlanePersistenceError(
      "Postgres control-plane storage requires the optional 'pg' package. Install it in apps/control-plane before using GAVIO_CONTROL_PLANE_STORAGE=postgres.",
    )
  }
  const Client = pg.Client ?? pg.default?.Client
  if (!Client) {
    throw new ControlPlanePersistenceError("Postgres control-plane storage could not find Client in the 'pg' package.")
  }
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  return new PostgresAdapter(client)
}

function resolveStorageMode(options) {
  if (options.storage) return options.storage
  if (process.env.GAVIO_CONTROL_PLANE_STORAGE) return process.env.GAVIO_CONTROL_PLANE_STORAGE
  if (options.databaseUrl || process.env.GAVIO_CONTROL_PLANE_DATABASE_URL) return 'postgres'
  if (options.sqlitePath || process.env.GAVIO_CONTROL_PLANE_SQLITE_PATH) return 'sqlite'
  return 'file'
}

function normalizeStorageMode(value) {
  const mode = String(value ?? 'file').toLowerCase().replaceAll('_', '-')
  if (mode === 'json' || mode === 'json-file') return 'file'
  if (mode === 'sqlite' || mode === 'sqlite3') return 'sqlite'
  if (mode === 'postgresql' || mode === 'pg') return 'postgres'
  return mode
}

function emptyState() {
  return Object.fromEntries(RESOURCE_NAMES.map((name) => [name, []]))
}

function normalizeState(value) {
  const state = emptyState()
  if (value && typeof value === 'object') {
    for (const key of RESOURCE_NAMES) {
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
    adminKeys: 'adminkey',
    teams: 'team',
    identityProviders: 'idp',
    policies: 'pol',
    policyRollouts: 'rollout',
    policyApprovals: 'approval',
    budgets: 'budget',
    events: 'evt',
    auditRecords: 'audit',
    configSnapshots: 'snap',
    retentionPolicies: 'retention',
  }[resource]
  const item = {
    id: input.id ?? nextVersion(idPrefix),
    createdAt: input.createdAt ?? now,
    updatedAt: input.updatedAt ?? now,
    ...input,
  }
  if (resource === 'keys') item.status = item.status ?? 'active'
  if (resource === 'adminKeys') {
    item.status = item.status ?? 'active'
    item.scopes = normalizeScopes(item.scopes)
  }
  if (resource === 'identityProviders') {
    item.status = item.status ?? 'active'
    item.protocol = String(item.protocol ?? 'oidc').toLowerCase()
  }
  if (resource === 'policyRollouts') {
    item.requiredApprovals = Number(item.requiredApprovals ?? (item.requiresApproval ? 1 : 0))
    item.requiresApproval = Boolean(item.requiresApproval || item.requiredApprovals > 0)
    item.status = item.status ?? (item.requiresApproval ? 'pending_approval' : 'active')
    if (item.requiresApproval && item.status === 'active' && item.requiredApprovals > 0) item.status = 'pending_approval'
  }
  if (resource === 'policyApprovals') item.decision = item.decision ?? 'approved'
  if (resource === 'events') item.kind = item.kind ?? 'runtime.event'
  if (resource === 'auditRecords') item.kind = item.kind ?? 'admin.audit'
  if (resource === 'retentionPolicies') {
    item.status = item.status ?? 'active'
    item.resource = item.resource ?? 'auditRecords'
    item.maxAgeDays = Number(item.maxAgeDays ?? 90)
  }
  return item
}

function nextVersion(prefix) {
  return `${prefix}_${randomUUID().slice(0, 8)}`
}

function redactKey(item) {
  const { keyHash, ...safe } = item
  return safe
}

function normalizeScopes(scopes) {
  const values = Array.isArray(scopes) ? scopes : scopes ? [scopes] : ['admin:read']
  return [...new Set(values.map((scope) => String(scope).trim()).filter(Boolean))].sort()
}

function verifyScopedAdminKey(records, token, scope) {
  const keyHash = hashRuntimeKey(token)
  const now = Date.now()
  const key = records.find((item) => {
    if (item.keyHash !== keyHash || item.status === 'revoked') return false
    if (item.expiresAt && Date.parse(item.expiresAt) <= now) return false
    return hasScope(item.scopes ?? [], scope)
  })
  return key ?? null
}

function hasScope(scopes, scope) {
  if (!scope) return true
  const values = normalizeScopes(scopes)
  if (values.includes('*') || values.includes(scope)) return true
  const [prefix] = String(scope).split(':')
  return values.includes(`${prefix}:*`)
}

function sanitizeIdentityProvider(item) {
  const out = {}
  for (const [key, value] of Object.entries(item)) {
    if (SENSITIVE_IDENTITY_KEYS.has(key)) {
      out[`${key}Hash`] = hashRuntimeKey(value)
      continue
    }
    out[key] = value
  }
  return out
}

function applyRolloutApprovalState(rollout, approvals) {
  const approvedCount = approvals.filter((item) => item.decision === 'approved').length
  const requiredApprovals = Number(rollout.requiredApprovals ?? (rollout.requiresApproval ? 1 : 0))
  const updated = {
    ...rollout,
    requiredApprovals,
    approvalCount: approvedCount,
    updatedAt: new Date().toISOString(),
  }
  if (requiredApprovals > 0 && approvedCount >= requiredApprovals && rollout.status !== 'rolled_back') {
    updated.status = 'active'
    updated.approvedAt = updated.approvedAt ?? updated.updatedAt
  }
  return updated
}

function isAdminResource(resource) {
  return !['events', 'auditRecords', 'configSnapshots'].includes(resource)
}

function buildAuditExport(records, filters) {
  const items = searchRecords(records, filters).map((record) => ({
    ...record,
    metadata: sanitizeMetadata(record.metadata ?? {}),
  }))
  return {
    schemaVersion: 'gavio.enterprise-admin.v1',
    exportedAt: new Date().toISOString(),
    filters,
    items,
  }
}

function applyRetentionToState(state, input = {}) {
  const dryRun = input.dryRun !== false
  const now = Date.parse(input.now ?? new Date().toISOString())
  const policies = (state.retentionPolicies ?? []).filter((policy) => policy.status !== 'disabled')
  const items = []
  const expiredByResource = Object.fromEntries(RETAINED_RESOURCES.map((resource) => [resource, new Set()]))

  for (const policy of policies) {
    const resources = retentionResources(policy.resource)
    const maxAgeDays = Number(policy.maxAgeDays ?? 90)
    const cutoff = now - maxAgeDays * 24 * 60 * 60 * 1000
    for (const resource of resources) {
      const expiredIds = (state[resource] ?? [])
        .filter((record) => Date.parse(record.createdAt ?? record.updatedAt ?? new Date().toISOString()) < cutoff)
        .map((record) => record.id)
      for (const id of expiredIds) expiredByResource[resource].add(id)
      items.push({
        policyId: policy.id,
        resource,
        maxAgeDays,
        expiredIds,
        expiredCount: expiredIds.length,
        applied: !dryRun,
      })
    }
  }

  if (!dryRun) {
    for (const [resource, ids] of Object.entries(expiredByResource)) {
      state[resource] = (state[resource] ?? []).filter((record) => !ids.has(record.id))
    }
  }

  return {
    schemaVersion: 'gavio.enterprise-admin.v1',
    dryRun,
    evaluatedAt: new Date(now).toISOString(),
    items,
  }
}

function retentionResources(resource) {
  if (resource === 'all') return RETAINED_RESOURCES
  if (RETAINED_RESOURCES.includes(resource)) return [resource]
  return []
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

function recordParams(resource, item) {
  return [
    resource,
    item.id,
    item.createdAt,
    item.updatedAt,
    stringOrNull(item.projectId),
    stringOrNull(item.environment),
    stringOrNull(item.policySource),
    stringOrNull(item.status),
    stringOrNull(item.traceId),
    stringOrNull(item.tenant),
    stringOrNull(item.feature),
    stringOrNull(item.model),
    stringOrNull(item.provider),
    stringOrNull(item.risk),
    stringOrNull(item.keyHash),
    JSON.stringify(item),
  ]
}

function stringOrNull(value) {
  return value === undefined || value === null ? null : String(value)
}

function httpError(status, message) {
  const error = new Error(message)
  error.status = status
  return error
}
