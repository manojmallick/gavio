/**
 * License / copyright detection validator (F-QUA-10).
 *
 * Flags known open-source license text (MIT, Apache-2.0, GPL-2.0/3.0,
 * BSD-3-Clause, MPL-2.0) in a model response before it lands in user code.
 * Matches against a shipped corpus of hashed 8-word shingles — no license text
 * is ever bundled. Detections surface in the guardrail outcome and, via the
 * guardrails interceptor, in the audit record.
 */

import { createHash } from 'node:crypto'
import { failed, passed, type OutputValidator, type ValidationResult } from '../validator.js'
import { LICENSE_FINGERPRINTS } from '../data/license-fingerprints.js'

const SHINGLE_N = 8

/** ASCII-lower-alnum tokeniser — must stay byte-identical across all SDKs. */
function normalizeTokens(text: string): string[] {
  const out: string[] = []
  let cur = ''
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    if (c >= 65 && c <= 90) cur += String.fromCharCode(c + 32)
    else if ((c >= 97 && c <= 122) || (c >= 48 && c <= 57)) cur += String.fromCharCode(c)
    else if (cur) {
      out.push(cur)
      cur = ''
    }
  }
  if (cur) out.push(cur)
  return out
}

function shingleHashes(tokens: string[]): Set<string> {
  const hashes = new Set<string>()
  for (let i = 0; i + SHINGLE_N <= tokens.length; i++) {
    const gram = tokens.slice(i, i + SHINGLE_N).join(' ')
    hashes.add(createHash('sha256').update(gram, 'utf8').digest('hex').slice(0, 16))
  }
  return hashes
}

export interface LicenseDetectorOptions {
  /** SPDX ids to check; defaults to the whole shipped corpus. */
  licenses?: string[]
  /** Minimum distinct shingle hits before a license is flagged (default 1). */
  minMatches?: number
}

/** Returns the sorted SPDX ids whose fingerprint appears in `content`. */
export function detectLicenses(content: string, options: LicenseDetectorOptions = {}): string[] {
  const { licenses, minMatches = 1 } = options
  const present = shingleHashes(normalizeTokens(content))
  const ids = licenses ?? Object.keys(LICENSE_FINGERPRINTS)
  const found: string[] = []
  for (const id of ids) {
    const fingerprints = LICENSE_FINGERPRINTS[id]
    if (!fingerprints) continue
    let hits = 0
    for (const h of fingerprints) {
      if (present.has(h)) {
        hits++
        if (hits >= minMatches) break
      }
    }
    if (hits >= minMatches) found.push(id)
  }
  return found.sort()
}

/** Fails if the content contains recognisable license text (F-QUA-10). */
export function licenseDetector(options: LicenseDetectorOptions = {}): OutputValidator {
  return {
    name: 'license_detector',
    validate(content: string): ValidationResult {
      const found = detectLicenses(content, options)
      if (found.length === 0) return passed()
      return failed(`license text detected: ${found.join(', ')}`)
    },
  }
}
