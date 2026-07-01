/** PII guard public surface. */

export { piiGuard, resolveOverlaps } from './guard.js'
export type { PiiGuardOptions } from './guard.js'
export { ScanContext } from './context.js'
export { ScannerRegistry, scannerTier } from './scanner.js'
export type { PiiScanner } from './scanner.js'
export { makeMatch, matchLength } from './match.js'
export type { PiiMatch } from './match.js'
export { PiiMode, Sensitivity } from '../../types.js'
export {
  bsnScanner,
  creditCardScanner,
  emailScanner,
  ibanScanner,
  ipAddressScanner,
  phoneScanner,
  secretScanner,
  ssnScanner,
  defaultScanners,
} from './scanners/index.js'
