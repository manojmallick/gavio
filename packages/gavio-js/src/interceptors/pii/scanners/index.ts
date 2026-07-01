/** Built-in tier-1 (regex) PII scanners. */

import type { PiiScanner } from '../scanner.js'
import { bsnScanner } from './bsn.js'
import { creditCardScanner } from './credit-card.js'
import { emailScanner } from './email.js'
import { ibanScanner } from './iban.js'
import { ipAddressScanner } from './ip-address.js'
import { phoneScanner } from './phone.js'
import { secretScanner } from './secret.js'
import { ssnScanner } from './ssn.js'

export { bsnScanner, validBsn } from './bsn.js'
export { creditCardScanner, luhnValid } from './credit-card.js'
export { emailScanner } from './email.js'
export { ibanScanner, validIban } from './iban.js'
export { ipAddressScanner } from './ip-address.js'
export { phoneScanner } from './phone.js'
export type { PhoneScannerOptions } from './phone.js'
export { secretScanner } from './secret.js'
export { ssnScanner } from './ssn.js'

/** The default scanner set wired into PiiGuard when none is supplied. */
export function defaultScanners(): PiiScanner[] {
  return [
    secretScanner(),
    emailScanner(),
    ibanScanner(),
    bsnScanner(),
    creditCardScanner(),
    ssnScanner(),
    phoneScanner(),
    ipAddressScanner(),
  ]
}
