/** Built-in tier-1 (regex) PII scanners. */

import type { PiiScanner } from '../scanner.js'
import { corePolicyPack, fintechPolicyPack } from '../policy-pack.js'

export { bsnScanner, validBsn } from './bsn.js'
export { creditCardScanner, luhnValid } from './credit-card.js'
export { emailScanner } from './email.js'
export { ibanScanner, validIban } from './iban.js'
export { ipAddressScanner } from './ip-address.js'
export { phoneScanner } from './phone.js'
export type { PhoneScannerOptions } from './phone.js'
export { routingNumberScanner, validRoutingNumber } from './routing-number.js'
export { secretScanner } from './secret.js'
export { ssnScanner } from './ssn.js'
export { swiftBicScanner } from './swift-bic.js'

/** The default scanner set wired into PiiGuard when none is supplied. */
export function defaultScanners(): PiiScanner[] {
  return corePolicyPack().scanners
}

/**
 * FinTech domain policy pack (F-SEC-01) — financial identifiers beyond the core
 * set: SWIFT/BIC and US ABA routing numbers. Compose with the defaults:
 * `piiGuard({ scanners: [...defaultScanners(), ...fintechScanners()] })`.
 * (IBAN is already in the default set.)
 */
export function fintechScanners(): PiiScanner[] {
  return fintechPolicyPack().scanners
}
