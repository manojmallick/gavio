/** PII guard public surface. */

export { piiGuard, resolveOverlaps } from './guard.js'
export type { PiiGuardOptions } from './guard.js'
export { ScanContext } from './context.js'
export { ScannerRegistry, scannerTier } from './scanner.js'
export type { PiiScanner } from './scanner.js'
export { makeMatch, matchLength } from './match.js'
export type { PiiMatch } from './match.js'
export { modalityGuard, ocrModalityScanner } from './modality.js'
export type {
  ModalityScanner,
  ModalityScanResult,
  ModalityGuardOptions,
  ModalityAction,
} from './modality.js'
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
  swiftBicScanner,
  routingNumberScanner,
  defaultScanners,
  fintechScanners,
} from './scanners/index.js'
