import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  piiGuard,
  fintechScanners,
  swiftBicScanner,
  routingNumberScanner,
} from '../../src/interceptors/pii/index.js'
import { validRoutingNumber } from '../../src/interceptors/pii/scanners/index.js'
import { ScanContext } from '../../src/interceptors/pii/context.js'
import { GavioTestKit } from '../../src/testing/index.js'

async function detect(text: string): Promise<string[]> {
  const kit = new GavioTestKit({
    interceptors: [piiGuard({ scanners: fintechScanners(), logEntityTypes: false })],
  })
  const result = await kit.run({ messages: [{ role: 'user', content: text }] })
  return [...new Set(result.ctx.piiEntityTypes)].sort()
}

describe('routing-number ABA checksum', () => {
  it('accepts valid routing numbers', () => {
    expect(validRoutingNumber('021000021')).toBe(true)
    expect(validRoutingNumber('111000025')).toBe(true)
  })
  it('rejects bad checksums and wrong lengths', () => {
    expect(validRoutingNumber('123456789')).toBe(false)
    expect(validRoutingNumber('000000000')).toBe(false)
    expect(validRoutingNumber('12345')).toBe(false)
  })
})

describe('swiftBicScanner (context-gated)', () => {
  it('matches a labelled BIC and captures just the code', () => {
    const m = swiftBicScanner().scan('SWIFT: DEUTDEFF500 now', new ScanContext()) as {
      value: string
      entityType: string
    }[]
    expect(m).toHaveLength(1)
    expect(m[0]!.value).toBe('DEUTDEFF500')
    expect(m[0]!.entityType).toBe('SWIFT_BIC')
  })
  it('does not match an unlabelled 8-letter word', () => {
    expect(swiftBicScanner().scan('the DATABASE was updated', new ScanContext())).toHaveLength(0)
  })
})

describe('routingNumberScanner', () => {
  it('matches a checksum-valid number and skips an invalid one', () => {
    expect(routingNumberScanner().scan('021000021', new ScanContext())).toHaveLength(1)
    expect(routingNumberScanner().scan('123456789', new ScanContext())).toHaveLength(0)
  })
})

describe('fintechScanners composition', () => {
  it('detects SWIFT/BIC and routing together, sorted', async () => {
    expect(await detect('SWIFT DEUTDEFF500 and routing 111000025')).toEqual([
      'ROUTING_NUMBER',
      'SWIFT_BIC',
    ])
  })
})

describe('shared test-vectors — pii/fintech-detection.json', () => {
  const url = new URL('../../../../test-vectors/pii/fintech-detection.json', import.meta.url)
  const vectors = JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as {
    cases: Array<{ id: string; text: string; expectedTypes: string[] }>
  }
  for (const c of vectors.cases) {
    it(`${c.id}`, async () => {
      expect(await detect(c.text)).toEqual(c.expectedTypes)
    })
  }
})
