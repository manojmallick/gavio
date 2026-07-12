// Gavio Domain Policy Pack Catalog.

import { Gateway } from 'gavio'
import {
  listPolicyPacks,
  loadPolicyPack,
  piiGuardFromPolicyPack,
} from 'gavio/interceptors/pii'

const healthcare = loadPolicyPack('healthcare')
const india = loadPolicyPack('regional/india')
const hr = loadPolicyPack('hr').withOverrides({
  detectors: {
    employee_id: {
      action: 'flag',
      severity: 'critical',
      redactionStrategy: 'hash',
    },
  },
})

console.log('catalog :', listPolicyPacks().join(', '))
console.log('signed  :', healthcare.id, healthcare.verifySignature())
console.log('override:', hr.manifest().detectors.find((d) => d.name === 'employee_id'))

const gw = new Gateway({ devMode: true }).use(
  piiGuardFromPolicyPack([healthcare, india, hr], { logEntityTypes: false }),
)

const r = await gw.complete({
  messages: [
    {
      role: 'user',
      content:
        'Patient MRN-123456 has member MEM-AB12CD34. PAN ABCDE1234F and Aadhaar 1234 5678 9012 are present. Template EMP-000000 is allowed, but EMP-123456 is real.',
    },
  ],
})

console.log('reply    :', r.content)
console.log('PII found:', [...r.audit.piiEntityTypes].sort())
