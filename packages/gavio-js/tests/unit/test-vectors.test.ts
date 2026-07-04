/**
 * Runs the shared cross-SDK test vectors from //test-vectors against the JS SDK.
 * Same JSON files the Python and Java SDKs run — parity is enforced, not assumed.
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ScanContext } from '../../src/interceptors/pii/context.js'
import type { PiiScanner } from '../../src/interceptors/pii/scanner.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import {
  bsnScanner,
  creditCardScanner,
  emailScanner,
  ibanScanner,
  ipAddressScanner,
  phoneScanner,
  secretScanner,
  ssnScanner,
} from '../../src/interceptors/pii/scanners/index.js'
import { GavioTestKit } from '../../src/testing/index.js'
import { detectLicenses } from '../../src/interceptors/guardrails/index.js'

function load(name: string): { cases: Record<string, unknown>[] } {
  const url = new URL(`../../../../test-vectors/pii/${name}`, import.meta.url)
  return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'))
}

function loadCategory(category: string, name: string): { cases: Record<string, unknown>[] } {
  const url = new URL(`../../../../test-vectors/${category}/${name}`, import.meta.url)
  return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'))
}

const SCANNERS: Record<string, () => PiiScanner> = {
  EMAIL: emailScanner,
  IBAN: ibanScanner,
  BSN: bsnScanner,
  CREDIT_CARD: creditCardScanner,
  PHONE: phoneScanner,
  IP_ADDRESS: ipAddressScanner,
  SSN: ssnScanner,
  SECRET: secretScanner,
}

const KNOWN_TYPES = Object.keys(SCANNERS).sort()

describe('shared test-vectors — pii/checksums.json', () => {
  for (const c of load('checksums.json').cases) {
    const { id, scanner, text, shouldMatch } = c as {
      id: string
      scanner: string
      text: string
      shouldMatch: boolean
    }
    it(`${id}`, async () => {
      const factory = SCANNERS[scanner]
      if (!factory) throw new Error(`unknown scanner in vector: ${scanner}`)
      const matches = await Promise.resolve(factory().scan(text, new ScanContext()))
      expect(matches.length > 0, `${scanner} on "${text}"`).toBe(shouldMatch)
    })
  }
})

describe('shared test-vectors — pii/detection.json', () => {
  for (const c of load('detection.json').cases) {
    const { id, text, expectedTypes } = c as {
      id: string
      text: string
      expectedTypes: string[]
    }
    it(`${id}`, async () => {
      const kit = new GavioTestKit({ interceptors: [piiGuard({ logEntityTypes: false })] })
      const result = await kit.run({ messages: [{ role: 'user', content: text }] })
      const detected = KNOWN_TYPES.filter((t) => result.piiDetected(t))
      expect(detected).toEqual(expectedTypes)
    })
  }
})

describe('shared test-vectors — license/detection.json', () => {
  for (const c of loadCategory('license', 'detection.json').cases) {
    const { id, text, expectedLicenses } = c as {
      id: string
      text: string
      expectedLicenses: string[]
    }
    it(`${id}`, () => {
      expect(detectLicenses(text)).toEqual(expectedLicenses)
    })
  }
})
