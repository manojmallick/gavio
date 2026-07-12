import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const CONTENT_KEYS = new Set(['messages', 'content', 'diff'])

const RESOURCE_NAMES = Object.freeze([
  'projects',
  'environments',
  'keys',
  'teams',
  'policies',
  'policyRollouts',
  'budgets',
  'events',
  'auditRecords',
  'configSnapshots',
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
    const item = withDefaults(resource, input)
    if (resource === 'events' || resource === 'auditRecords') {
      item.metadata = sanitizeMetadata(item.metadata ?? {})
    }
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

  async listKeys() {
    return this.state.keys.map(redactKey)
  }

  async verifyRuntimeKey(token) {
    const keyHash = hashRuntimeKey(token)
    return this.state.keys.find((item) => item.keyHash === keyHash && item.status !== 'revoked')
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
    const item = withDefaults(resource, input)
    if (resource === 'events' || resource === 'auditRecords') {
      item.metadata = sanitizeMetadata(item.metadata ?? {})
    }
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

  async listKeys() {
    return (await this.adapter.listRecords('keys')).map(redactKey)
  }

  async verifyRuntimeKey(token) {
    const keyHash = hashRuntimeKey(token)
    return (await this.adapter.listRecords('keys')).find(
      (item) => item.keyHash === keyHash && item.status !== 'revoked',
    )
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
