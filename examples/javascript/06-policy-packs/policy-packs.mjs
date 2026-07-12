// Gavio Policy Packs - core PII, FinTech, and custom regex rules.

import { Gateway } from 'gavio'
import {
  corePolicyPack,
  customPolicyPack,
  fintechPolicyPack,
  piiGuard,
  policyPackScanners,
} from 'gavio/interceptors/pii'

const core = corePolicyPack()
const fintech = fintechPolicyPack()
const internal = customPolicyPack({
  id: 'acme.internal',
  name: 'Acme Internal IDs',
  rules: [
    {
      name: 'employee_id',
      entityType: 'EMPLOYEE_ID',
      pattern: /\bEMP-[0-9]{6}\b/g,
      confidence: 0.92,
      replacementPrefix: 'EMPLOYEE_ID',
      action: 'flag',
      redactionStrategy: 'hash',
      label: 'INTERNAL_IDENTIFIER',
    },
  ],
  defaultAction: 'flag',
  redactionStrategy: 'hash',
  auditLabels: ['INTERNAL_IDENTIFIER'],
})

console.log('packs:', core.manifest().id, fintech.manifest().id, internal.manifest().id)
console.log('fintech detectors:', fintech.manifest().detectors.map((d) => d.entityType))

const gw = new Gateway({ devMode: true }).use(
  piiGuard({ scanners: policyPackScanners(core, fintech, internal) }),
)

const r = await gw.complete({
  messages: [
    {
      role: 'user',
      content: 'Wire SWIFT DEUTDEFF500 routing 111000025 for EMP-123456 and email jan@example.com.',
    },
  ],
})

console.log('reply    :', r.content)
console.log('PII found:', [...r.audit.piiEntityTypes].sort())
console.log('fired    :', r.interceptorsFired)
