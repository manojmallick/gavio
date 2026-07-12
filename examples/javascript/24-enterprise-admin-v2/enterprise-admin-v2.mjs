import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { startControlPlane } from '../../../apps/control-plane/src/server.mjs'

const started = await startControlPlane({
  port: 0,
  statePath: join(mkdtempSync(join(tmpdir(), 'gavio-enterprise-admin-')), 'state.json'),
})

try {
  const base = started.url
  await post(base, '/api/identity-providers', {
    id: 'idp_acme_oidc',
    protocol: 'oidc',
    issuer: 'https://login.acme.example',
    clientId: 'gavio-admin',
    clientSecret: 'example-secret-is-hashed',
    domainHints: ['acme.example'],
    roleMapping: { admins: 'admin', auditors: 'auditor' },
  })

  const adminKey = await post(base, '/api/admin-keys', {
    id: 'adminkey_enterprise_ops',
    name: 'Enterprise ops automation',
    scopes: ['policy:write', 'policy:approve', 'audit:export', 'retention:write'],
  })
  const auth = { authorization: `Bearer ${adminKey.token}` }

  await post(base, '/api/projects', { id: 'proj_enterprise', name: 'Enterprise' })
  await post(base, '/api/environments', { id: 'env_enterprise_prod', projectId: 'proj_enterprise', name: 'prod' })
  await post(
    base,
    '/api/policies',
    {
      id: 'pol_enterprise',
      name: 'Enterprise policy',
      policyPack: 'enterprise',
      rules: [{ id: 'enterprise-redact', action: 'redact' }],
    },
    auth,
  )
  await post(
    base,
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
    auth,
  )
  const approval = await post(
    base,
    '/api/policy-rollouts/rollout_enterprise_prod/approvals',
    { decision: 'approved', metadata: { ticket: 'SEC-42', content: 'stripped before storage' } },
    auth,
  )

  await post(base, '/api/audit-records', {
    id: 'audit_enterprise_export',
    action: 'review',
    resource: 'policyRollouts',
    resourceId: 'rollout_enterprise_prod',
    traceId: 'trace-enterprise-audit',
    tenant: 'acme',
    feature: 'enterprise-admin',
    metadata: { decision: { status: 'approved' }, content: 'raw audit text' },
  })
  const auditExport = await text(base, '/api/audit-export?format=jsonl&trace=trace-enterprise-audit', auth)

  await post(base, '/api/events', {
    id: 'evt_enterprise_old',
    createdAt: '2026-01-01T00:00:00.000Z',
    traceId: 'trace-retention-old',
    metadata: { decision: { action: 'allow' }, content: 'old raw event' },
  })
  await post(base, '/api/retention-policies', { id: 'retention_events_30d', resource: 'events', maxAgeDays: 30 }, auth)
  const retention = await post(base, '/api/retention/apply', { dryRun: true, now: '2026-07-12T00:00:00.000Z' }, auth)

  console.log('control plane:', base)
  console.log('admin key prefix:', adminKey.prefix)
  console.log('rollout status:', approval.rollout.status)
  console.log('audit export lines:', auditExport.trim().split('\n').length)
  console.log('retention expired:', retention.items[0]?.expiredIds.join(', '))
} finally {
  await new Promise((resolve) => started.server.close(resolve))
}

async function post(base, path, body, headers = {}) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`${path}: ${await response.text()}`)
  return response.json()
}

async function text(base, path, headers = {}) {
  const response = await fetch(`${base}${path}`, { headers })
  if (!response.ok) throw new Error(`${path}: ${await response.text()}`)
  return response.text()
}
