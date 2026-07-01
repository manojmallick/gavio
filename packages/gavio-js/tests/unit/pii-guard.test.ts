import { describe, it, expect } from 'vitest'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import {
  emailScanner,
  ibanScanner,
} from '../../src/interceptors/pii/scanners/index.js'
import { GavioTestKit } from '../../src/testing/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import { PiiMode, Sensitivity } from '../../src/types.js'

describe('PiiGuard redact + restore', () => {
  it('redacts an IBAN before the provider and restores it in the reply', async () => {
    const kit = new GavioTestKit({
      interceptors: [piiGuard({ scanners: [ibanScanner()], logEntityTypes: false })],
      provider: mockProvider({ response: 'I processed [IBAN_1]' }),
    })
    const result = await kit.run({
      messages: [{ role: 'user', content: 'Transfer from NL91ABNA0417164300' }],
    })
    expect(result.preRequestText()).not.toContain('NL91ABNA0417164300')
    expect(result.preRequestText()).toContain('[IBAN_1]')
    expect(result.piiDetected('IBAN')).toBe(true)
    // restore brings the original value back in the response
    expect(result.response.content).toContain('NL91ABNA0417164300')
  })

  it('redacts multiple distinct entity types with stable indices', async () => {
    const kit = new GavioTestKit({
      interceptors: [
        piiGuard({ scanners: [emailScanner(), ibanScanner()], logEntityTypes: false }),
      ],
      provider: mockProvider({ response: 'ok [EMAIL_1] [IBAN_1]' }),
    })
    const result = await kit.run({
      messages: [
        { role: 'user', content: 'mail a@b.com and pay NL91ABNA0417164300' },
      ],
    })
    expect(result.preRequestText()).toContain('[EMAIL_1]')
    expect(result.preRequestText()).toContain('[IBAN_1]')
    expect(result.response.content).toContain('a@b.com')
    expect(result.response.content).toContain('NL91ABNA0417164300')
  })
})

describe('PiiGuard mask mode', () => {
  it('replaces with asterisks and does not restore', async () => {
    const kit = new GavioTestKit({
      interceptors: [
        piiGuard({
          scanners: [emailScanner()],
          mode: PiiMode.MASK,
          logEntityTypes: false,
        }),
      ],
      provider: mockProvider(),
    })
    const result = await kit.run({
      messages: [{ role: 'user', content: 'email a@b.com' }],
    })
    expect(result.preRequestText()).not.toContain('a@b.com')
    expect(result.preRequestText()).toContain('*')
    expect(result.piiDetected('EMAIL')).toBe(true)
  })
})

describe('PiiGuard block mode', () => {
  it('throws PiiBlockedError when PII is present', async () => {
    const kit = new GavioTestKit({
      interceptors: [
        piiGuard({
          scanners: [emailScanner()],
          mode: PiiMode.BLOCK,
          logEntityTypes: false,
        }),
      ],
      provider: mockProvider(),
    })
    await expect(
      kit.run({ messages: [{ role: 'user', content: 'email a@b.com' }] }),
    ).rejects.toThrow(/PII detected/)
  })
})

describe('PiiGuard dry-run', () => {
  it('detects but does not redact when dryRun is set', async () => {
    const kit = new GavioTestKit({
      interceptors: [
        piiGuard({ scanners: [emailScanner()], dryRun: true, logEntityTypes: false }),
      ],
      provider: mockProvider(),
    })
    const result = await kit.run({
      messages: [{ role: 'user', content: 'email a@b.com' }],
    })
    expect(result.preRequestText()).toContain('a@b.com')
    expect(result.piiDetected('EMAIL')).toBe(true)
  })
})

describe('PiiGuard sensitivity floor', () => {
  it('permissive floor drops low-confidence phone matches', async () => {
    const { phoneScanner } = await import(
      '../../src/interceptors/pii/scanners/index.js'
    )
    const kit = new GavioTestKit({
      interceptors: [
        piiGuard({
          scanners: [phoneScanner()],
          sensitivity: Sensitivity.PERMISSIVE,
          logEntityTypes: false,
        }),
      ],
      provider: mockProvider(),
    })
    const result = await kit.run({
      messages: [{ role: 'user', content: 'call +31 20 123 4567' }],
    })
    // phone confidence is 0.85, below the 0.9 permissive floor -> not detected
    expect(result.piiDetected('PHONE')).toBe(false)
  })
})
