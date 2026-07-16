const ENDPOINTS = {
  projects: '/api/projects',
  environments: '/api/environments',
  keys: '/api/keys',
  adminKeys: '/api/admin-keys',
  identityProviders: '/api/identity-providers',
  policies: '/api/policies',
  policyRollouts: '/api/policy-rollouts',
  policyApprovals: '/api/policy-approvals',
  budgets: '/api/budgets',
  events: '/api/events',
  auditRecords: '/api/audit-records',
  configSnapshots: '/api/config-snapshots',
  retentionPolicies: '/api/retention-policies',
  workflowReleases: '/api/workflow-releases',
}

const VIEWS = [
  ['overview', 'Overview'],
  ['setup', 'Projects'],
  ['keys', 'Keys'],
  ['policies', 'Policies'],
  ['budgets', 'Budgets'],
  ['events', 'Events'],
  ['audit', 'Audit'],
  ['releases', 'Releases'],
  ['admin', 'Enterprise'],
  ['retention', 'Retention'],
]

const FILTERS = {
  events: ['trace', 'tenant', 'feature', 'model', 'provider', 'risk', 'policySource', 'status'],
  auditRecords: ['trace', 'tenant', 'feature', 'resource', 'status'],
  workflowReleases: ['policySource', 'releaseVersion', 'status'],
}

const state = {
  view: 'overview',
  health: null,
  overview: null,
  data: Object.fromEntries(Object.keys(ENDPOINTS).map((key) => [key, []])),
  filters: {
    events: {},
    auditRecords: {},
    workflowReleases: {},
  },
  secret: null,
}

const TABLES = {
  projects: ['id', 'name', 'owner', 'createdAt'],
  environments: ['id', 'projectId', 'name', 'createdAt'],
  keys: ['id', 'projectId', 'environment', 'name', 'prefix', 'status'],
  adminKeys: ['id', 'name', 'prefix', 'scopes', 'status'],
  identityProviders: ['id', 'protocol', 'issuer', 'domainHints', 'status'],
  policies: ['id', 'name', 'policyPack', 'rules', 'createdAt'],
  policyRollouts: ['id', 'projectId', 'environment', 'policySource', 'policyId', 'status', 'percentage'],
  policyApprovals: ['id', 'rolloutId', 'actor', 'decision', 'metadata', 'createdAt'],
  budgets: ['id', 'projectId', 'scopeType', 'limitUsd', 'window', 'action'],
  events: ['id', 'traceId', 'tenant', 'feature', 'provider', 'model', 'risk', 'policySource', 'metadata'],
  auditRecords: ['id', 'actor', 'action', 'resource', 'resourceId', 'traceId', 'tenant', 'metadata'],
  retentionPolicies: ['id', 'resource', 'maxAgeDays', 'status'],
  workflowReleases: ['id', 'workflowId', 'releaseVersion', 'status', 'policySource', 'profileId', 'workflowHash', 'metadata'],
}

const FORM_CONFIGS = {
  project: {
    title: 'Project',
    endpoint: ENDPOINTS.projects,
    fields: [
      text('id', 'ID', 'proj_support'),
      text('name', 'Name', 'Support'),
      text('owner', 'Owner', 'platform', true),
    ],
  },
  environment: {
    title: 'Environment',
    endpoint: ENDPOINTS.environments,
    fields: [
      text('id', 'ID', 'env_support_prod'),
      text('projectId', 'Project ID', 'proj_support'),
      text('name', 'Name', 'prod'),
    ],
  },
  runtimeKey: {
    title: 'Runtime Key',
    endpoint: ENDPOINTS.keys,
    fields: [
      text('id', 'ID', 'key_support_prod', true),
      text('projectId', 'Project ID', 'proj_support'),
      text('environment', 'Environment', 'prod'),
      text('name', 'Name', 'prod runtime'),
    ],
  },
  adminKey: {
    title: 'Admin Key',
    endpoint: ENDPOINTS.adminKeys,
    fields: [
      text('id', 'ID', 'adminkey_ops', true),
      text('name', 'Name', 'ops automation'),
      text('scopes', 'Scopes', 'policy:write,policy:approve,audit:export,retention:write', false, 'list'),
    ],
  },
  policy: {
    title: 'Policy',
    endpoint: ENDPOINTS.policies,
    fields: [
      text('id', 'ID', 'pol_support'),
      text('name', 'Name', 'Support policy'),
      text('policyPack', 'Policy pack', 'support'),
      jsonField('rules', 'Rules JSON', '[{"id":"support-email","action":"redact"}]'),
    ],
  },
  rollout: {
    title: 'Rollout',
    endpoint: ENDPOINTS.policyRollouts,
    fields: [
      text('id', 'ID', 'rollout_support_prod'),
      text('projectId', 'Project ID', 'proj_support'),
      text('environment', 'Environment', 'prod'),
      text('policySource', 'Policy source', 'project:prod-support'),
      text('policyId', 'Policy ID', 'pol_support'),
      select('status', 'Status', ['', 'active', 'pending_approval', 'paused', 'rolled_back']),
      numberField('percentage', 'Percentage', '100', true),
      numberField('cacheTtlSeconds', 'Cache TTL seconds', '300', true),
      checkbox('requiresApproval', 'Requires approval'),
      numberField('requiredApprovals', 'Required approvals', '1', true),
    ],
  },
  budget: {
    title: 'Budget',
    endpoint: ENDPOINTS.budgets,
    fields: [
      text('id', 'ID', 'budget_support_day'),
      text('projectId', 'Project ID', 'proj_support'),
      select('scopeType', 'Scope type', ['project', 'environment', 'global']),
      numberField('limitUsd', 'Limit USD', '25'),
      text('window', 'Window', 'day'),
      select('action', 'Action', ['warn', 'downgrade', 'fallback', 'block']),
    ],
  },
  identityProvider: {
    title: 'Identity Provider',
    endpoint: ENDPOINTS.identityProviders,
    fields: [
      text('id', 'ID', 'idp_acme_oidc'),
      select('protocol', 'Protocol', ['oidc', 'saml']),
      text('issuer', 'Issuer', 'https://login.acme.example'),
      text('clientId', 'Client ID', 'gavio-admin', true),
      text('domainHints', 'Domain hints', 'acme.example', true, 'list'),
    ],
  },
  retentionPolicy: {
    title: 'Retention Policy',
    endpoint: ENDPOINTS.retentionPolicies,
    fields: [
      text('id', 'ID', 'retention_events_30d', true),
      select('resource', 'Resource', ['events', 'auditRecords', 'configSnapshots', 'all']),
      numberField('maxAgeDays', 'Max age days', '30'),
      select('status', 'Status', ['active', 'disabled']),
    ],
  },
}

document.getElementById('refresh').addEventListener('click', () => run(refresh()))
document.getElementById('demo').addEventListener('click', () => run(seedDemo()))

document.addEventListener('click', (event) => run(handleClick(event)))
document.addEventListener('submit', (event) => run(handleSubmit(event)))

refresh().catch(showError)

async function handleClick(event) {
  const viewButton = event.target.closest('[data-view]')
  if (viewButton) {
    state.view = viewButton.dataset.view
    render()
    document.getElementById('app').focus()
    return
  }

  const resetButton = event.target.closest('[data-reset-filter]')
  if (resetButton) {
    state.filters[resetButton.dataset.resetFilter] = {}
    await refresh()
  }
}

async function handleSubmit(event) {
  if (!event.target.matches('form')) return
  event.preventDefault()
  const form = event.target
  if (form.dataset.form) {
    await submitConfiguredForm(form)
    return
  }
  if (form.dataset.filterForm) {
    state.filters[form.dataset.filterForm] = formValues(form, FILTERS[form.dataset.filterForm].map((name) => text(name, labelFor(name), '', true)))
    await refresh()
    return
  }
  if (form.dataset.workflowImport) {
    await importWorkflowRelease(form)
  }
}

async function refresh() {
  const [health, overview] = await Promise.all([api('/health'), api('/api/overview')])
  state.health = health
  state.overview = overview
  document.getElementById('status').textContent = health.ok ? `online / ${health.storage}` : 'offline'
  document.getElementById('demo').hidden = !overview.demoEnabled

  const entries = await Promise.all(
    Object.entries(ENDPOINTS).map(async ([key, endpoint]) => {
      const query = state.filters[key] ?? {}
      const response = await api(withQuery(endpoint, query))
      return [key, response.items ?? []]
    }),
  )
  state.data = Object.fromEntries(entries)
  render()
}

async function seedDemo() {
  const result = await api('/api/demo/seed', { method: 'POST', body: {} })
  state.secret = {
    title: 'Demo runtime key',
    token: result.runtimeToken,
    detail: `Policy source: ${result.policySource}`,
  }
  toast('Demo records created')
  await refresh()
}

async function submitConfiguredForm(form) {
  const config = FORM_CONFIGS[form.dataset.form]
  const payload = formValues(form, config.fields)
  const result = await api(config.endpoint, { method: 'POST', body: payload })
  if (result.token) {
    state.secret = {
      title: `${config.title} token`,
      token: result.token,
      detail: result.policySource ? `Policy source: ${result.policySource}` : '',
    }
  }
  form.reset()
  toast(`${config.title} saved`)
  await refresh()
}

async function importWorkflowRelease(form) {
  const artifactText = new FormData(form).get('artifact')
  const artifact = JSON.parse(String(artifactText))
  const result = await api('/api/workflow-releases/import', { method: 'POST', body: artifact })
  form.reset()
  toast(`Imported ${result.id}`)
  await refresh()
}

function render() {
  renderNav()
  const app = document.getElementById('app')
  app.innerHTML = [
    renderSecret(),
    {
      overview: renderOverview,
      setup: renderSetup,
      keys: renderKeys,
      policies: renderPolicies,
      budgets: renderBudgets,
      events: renderEvents,
      audit: renderAudit,
      releases: renderReleases,
      admin: renderAdmin,
      retention: renderRetention,
    }[state.view](),
  ].join('')
}

function renderNav() {
  document.getElementById('nav').innerHTML = VIEWS.map(
    ([id, label]) => `<button type="button" data-view="${id}" class="${state.view === id ? 'active' : ''}">${escapeHtml(label)}</button>`,
  ).join('')
}

function renderOverview() {
  const overview = state.overview ?? { counts: {} }
  const counts = [
    ['projects', 'Projects'],
    ['environments', 'Environments'],
    ['keys', 'Runtime keys'],
    ['policies', 'Policies'],
    ['policyRollouts', 'Rollouts'],
    ['budgets', 'Budgets'],
    ['events', 'Runtime events'],
    ['auditRecords', 'Audit records'],
    ['workflowReleases', 'Workflow releases'],
  ]
  return `${viewHead('Overview', 'Runtime configuration, rollout posture, and recent metadata-safe activity.')}
    <section class="band">
      <div class="metrics">
        ${counts
          .map(([key, label]) => `<article class="metric"><strong>${overview.counts[key] ?? 0}</strong><span>${label}</span></article>`)
          .join('')}
      </div>
    </section>
    <div class="grid">
      ${resourcePanel('Active rollouts', overview.activeRollouts ?? [], ['id', 'policySource', 'status', 'percentage'])}
      ${resourcePanel('Workflow releases', overview.latestWorkflowReleases ?? [], ['workflowId', 'releaseVersion', 'status', 'policySource'])}
      ${resourcePanel('Recent events', overview.recentEvents ?? [], ['traceId', 'feature', 'provider', 'risk'])}
      ${resourcePanel('Recent audit', overview.recentAuditRecords ?? [], ['action', 'resource', 'resourceId', 'actor'])}
    </div>`
}

function renderSetup() {
  return `${viewHead('Projects', 'Project and environment scopes used by runtime keys, budgets, and policy rollouts.')}
    ${forms(['project', 'environment'])}
    ${resourcePanel('Projects', state.data.projects, TABLES.projects)}
    ${resourcePanel('Environments', state.data.environments, TABLES.environments)}`
}

function renderKeys() {
  return `${viewHead('Keys', 'Plaintext keys are returned once; stored records keep only prefixes and hashes.')}
    ${forms(['runtimeKey'])}
    ${resourcePanel('Runtime keys', state.data.keys, TABLES.keys)}`
}

function renderPolicies() {
  return `${viewHead('Policies', 'Policy documents and rollout assignments for runtime config.')}
    ${forms(['policy', 'rollout'])}
    ${resourcePanel('Policies', state.data.policies, TABLES.policies)}
    ${resourcePanel('Rollouts', state.data.policyRollouts, TABLES.policyRollouts)}
    ${resourcePanel('Approvals', state.data.policyApprovals, TABLES.policyApprovals)}`
}

function renderBudgets() {
  return `${viewHead('Budgets', 'Cost-governance limits used in runtime config responses.')}
    ${forms(['budget'])}
    ${resourcePanel('Budgets', state.data.budgets, TABLES.budgets)}`
}

function renderEvents() {
  return `${viewHead('Events', 'Metadata-only runtime events with trace, tenant, feature, provider, model, and risk search.')}
    ${filterForm('events')}
    ${resourcePanel('Runtime events', state.data.events, TABLES.events)}`
}

function renderAudit() {
  return `${viewHead('Audit', 'Admin actions, approvals, and searchable audit records.')}
    ${filterForm('auditRecords')}
    ${resourcePanel('Audit records', state.data.auditRecords, TABLES.auditRecords)}`
}

function renderReleases() {
  return `${viewHead('Releases', 'Platform workflow release records imported from metadata-only release artifacts.')}
    <section class="band">
      <h3>Import workflow release</h3>
      <form data-workflow-import="true">
        <label>Artifact JSON<textarea name="artifact" spellcheck="false" required>${escapeHtml(sampleWorkflowRelease())}</textarea></label>
        <button class="primary" type="submit">Import</button>
      </form>
    </section>
    ${filterForm('workflowReleases')}
    ${resourcePanel('Workflow releases', state.data.workflowReleases, TABLES.workflowReleases)}`
}

function renderAdmin() {
  return `${viewHead('Enterprise', 'Scoped admin keys and OIDC/SAML-lite provider metadata.')}
    ${forms(['adminKey', 'identityProvider'])}
    ${resourcePanel('Admin keys', state.data.adminKeys, TABLES.adminKeys)}
    ${resourcePanel('Identity providers', state.data.identityProviders, TABLES.identityProviders)}`
}

function renderRetention() {
  return `${viewHead('Retention', 'Retention windows for events, audit records, and config snapshots.')}
    ${forms(['retentionPolicy'])}
    ${resourcePanel('Retention policies', state.data.retentionPolicies, TABLES.retentionPolicies)}
    ${resourcePanel('Config snapshots', state.data.configSnapshots, ['id', 'policySource', 'projectId', 'environment', 'configVersion', 'createdAt'])}`
}

function forms(names) {
  return `<section class="band"><h3>Create</h3><div class="forms">${names.map((name) => configuredForm(name)).join('')}</div></section>`
}

function configuredForm(name) {
  const config = FORM_CONFIGS[name]
  return `<form data-form="${name}">
    <h3>${escapeHtml(config.title)}</h3>
    ${config.fields.map(renderField).join('')}
    <button class="primary" type="submit">Save ${escapeHtml(config.title)}</button>
  </form>`
}

function filterForm(resource) {
  const filters = state.filters[resource] ?? {}
  return `<section class="band">
    <h3>Filters</h3>
    <form class="filters" data-filter-form="${resource}">
      ${FILTERS[resource]
        .map((name) => `<label>${escapeHtml(labelFor(name))}<input name="${name}" value="${escapeAttr(filters[name] ?? '')}" /></label>`)
        .join('')}
      <button class="primary" type="submit">Apply</button>
      <button type="button" data-reset-filter="${resource}">Clear</button>
    </form>
  </section>`
}

function resourcePanel(title, items, columns) {
  return `<section class="band"><h3>${escapeHtml(title)}</h3>${table(items, columns)}</section>`
}

function table(items, columns) {
  if (!items || items.length === 0) return '<p class="empty">No records yet.</p>'
  return `<div class="table-wrap"><table><thead><tr>${columns
    .map((column) => `<th>${escapeHtml(labelFor(column))}</th>`)
    .join('')}</tr></thead><tbody>${items
    .map((item) => `<tr>${columns.map((column) => `<td>${cell(item[column], column)}</td>`).join('')}</tr>`)
    .join('')}</tbody></table></div>`
}

function cell(value, column) {
  if (value === undefined || value === null || value === '') return '<span class="empty">-</span>'
  if (column === 'status' || column === 'decision' || column === 'risk') {
    return `<span class="pill ${className(String(value))}">${escapeHtml(value)}</span>`
  }
  if (Array.isArray(value) || typeof value === 'object') return `<pre>${escapeHtml(JSON.stringify(value, null, 2))}</pre>`
  const text = String(value)
  if (text.length > 34 || /^[a-z]+_[a-z0-9_:-]+$/i.test(text) || text.startsWith('sha256:')) {
    return `<code>${escapeHtml(text)}</code>`
  }
  return escapeHtml(text)
}

function renderSecret() {
  if (!state.secret) return ''
  return `<section class="band secret">
    <h3>${escapeHtml(state.secret.title)}</h3>
    <p><code>${escapeHtml(state.secret.token)}</code></p>
    ${state.secret.detail ? `<p class="empty">${escapeHtml(state.secret.detail)}</p>` : ''}
  </section>`
}

function viewHead(title, detail) {
  return `<div class="view-head"><div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(detail)}</p></div></div>`
}

function renderField(field) {
  if (field.type === 'select') {
    return `<label>${escapeHtml(field.label)}<select name="${field.name}">${field.options
      .map((option) => `<option value="${escapeAttr(option)}">${escapeHtml(option || 'default')}</option>`)
      .join('')}</select></label>`
  }
  if (field.type === 'textarea') {
    return `<label>${escapeHtml(field.label)}<textarea name="${field.name}" spellcheck="false">${escapeHtml(field.value ?? '')}</textarea></label>`
  }
  if (field.type === 'checkbox') {
    return `<label class="checkbox-label"><input type="checkbox" name="${field.name}" /> ${escapeHtml(field.label)}</label>`
  }
  return `<label>${escapeHtml(field.label)}<input name="${field.name}" type="${field.type}" value="${escapeAttr(field.value ?? '')}" ${field.optional ? '' : 'required'} /></label>`
}

function formValues(form, fields) {
  const data = new FormData(form)
  const payload = {}
  for (const field of fields) {
    if (field.type === 'checkbox') {
      if (form.elements[field.name]?.checked) payload[field.name] = true
      continue
    }
    const raw = String(data.get(field.name) ?? '').trim()
    if (!raw && field.optional) continue
    if (!raw) continue
    if (field.parse === 'json') {
      payload[field.name] = JSON.parse(raw)
    } else if (field.parse === 'number') {
      payload[field.name] = Number(raw)
    } else if (field.parse === 'list') {
      payload[field.name] = raw.split(',').map((item) => item.trim()).filter(Boolean)
    } else {
      payload[field.name] = raw
    }
  }
  return payload
}

async function api(path, options = {}) {
  const init = { method: options.method ?? 'GET', headers: options.headers ?? {} }
  if (options.body !== undefined) {
    init.headers = { 'content-type': 'application/json', ...init.headers }
    init.body = JSON.stringify(options.body)
  }
  const response = await fetch(path, init)
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `${response.status} ${response.statusText}`)
  }
  return response.json()
}

function withQuery(path, query) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null && String(value).trim() !== '') params.set(key, value)
  }
  const value = params.toString()
  return value ? `${path}?${value}` : path
}

function text(name, label, value, optional = false, parse) {
  return { type: 'text', name, label, value, optional, parse }
}

function numberField(name, label, value, optional = false) {
  return { type: 'number', name, label, value, optional, parse: 'number' }
}

function jsonField(name, label, value) {
  return { type: 'textarea', name, label, value, parse: 'json' }
}

function checkbox(name, label) {
  return { type: 'checkbox', name, label, optional: true }
}

function select(name, label, options) {
  return { type: 'select', name, label, options, optional: options[0] === '' }
}

function labelFor(name) {
  return String(name)
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function sampleWorkflowRelease() {
  return JSON.stringify(
    {
      schemaVersion: 'gavio.platform-workflow-release.v1',
      workflowId: 'support-platform-release',
      generatedAt: '2026-07-16T12:00:00Z',
      release: { version: '3.1.0', tag: 'v3.1.0', commit: 'local-example' },
      passed: true,
      reasons: [],
      prompts: { releaseBundles: [] },
      evals: [],
      policies: [],
      trust: { valid: true, runtime: { policySource: 'project:prod-support' } },
      runtimeProfile: { valid: true, readiness: { ready: true } },
      metadata: { owner: 'platform' },
      workflowHash: 'sha256:localexample',
    },
    null,
    2,
  )
}

function toast(message) {
  const node = document.getElementById('toast')
  node.textContent = message
  node.hidden = false
  clearTimeout(toast.timer)
  toast.timer = setTimeout(() => {
    node.hidden = true
  }, 3500)
}

function showError(error) {
  document.getElementById('status').textContent = 'error'
  toast(error.message)
}

function run(promise) {
  Promise.resolve(promise).catch(showError)
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => htmlEscapes[char])
}

function escapeAttr(value) {
  return escapeHtml(value)
}

function className(value) {
  return value.toLowerCase().replace(/[^a-z0-9_-]/g, '_')
}

const htmlEscapes = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#039;',
}
