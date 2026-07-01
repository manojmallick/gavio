import { describe, it, expect } from 'vitest'
import { ScanContext } from '../../src/interceptors/pii/context.js'
import type { PiiMatch } from '../../src/interceptors/pii/match.js'
import type { PiiScanner } from '../../src/interceptors/pii/scanner.js'
import {
  emailScanner,
  ibanScanner,
  validIban,
  bsnScanner,
  validBsn,
  creditCardScanner,
  luhnValid,
  phoneScanner,
  ipAddressScanner,
  ssnScanner,
  secretScanner,
} from '../../src/interceptors/pii/scanners/index.js'

const ctx = () => new ScanContext()

/** v0.1.0 scanners are synchronous; assert and narrow the union for the test. */
function run(scanner: PiiScanner, text: string, c = ctx()): PiiMatch[] {
  const out = scanner.scan(text, c)
  if (out instanceof Promise) throw new Error('expected sync scanner')
  return out
}

describe('emailScanner', () => {
  it('detects an email and produces a typed replacement', () => {
    const m = run(emailScanner(), 'contact jan@example.com please')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('EMAIL')
    expect(m[0]!.value).toBe('jan@example.com')
    expect(m[0]!.replacement).toBe('[EMAIL_1]')
  })

  it('does not match plain text', () => {
    expect(run(emailScanner(), 'no addresses here')).toHaveLength(0)
  })
})

describe('ibanScanner', () => {
  it('accepts a valid IBAN (checksum)', () => {
    expect(validIban('NL91ABNA0417164300')).toBe(true)
    const m = run(ibanScanner(), 'Transfer from NL91ABNA0417164300')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('IBAN')
  })

  it('rejects an IBAN with a bad checksum', () => {
    expect(validIban('NL00ABNA0417164300')).toBe(false)
    expect(run(ibanScanner(), 'NL00ABNA0417164300')).toHaveLength(0)
  })

  it('validates a spaced German IBAN', () => {
    expect(validIban('DE89 3704 0044 0532 0130 00')).toBe(true)
  })
})

describe('bsnScanner (11-proef)', () => {
  it('accepts a valid BSN', () => {
    expect(validBsn('111222333')).toBe(true)
    const m = run(bsnScanner(), 'BSN 111222333 on file')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('BSN')
  })

  it('rejects an invalid BSN', () => {
    expect(validBsn('123456789')).toBe(false)
    expect(run(bsnScanner(), '123456789')).toHaveLength(0)
  })
})

describe('creditCardScanner (Luhn)', () => {
  it('accepts a valid card', () => {
    expect(luhnValid('4111111111111111')).toBe(true)
    const m = run(creditCardScanner(), 'card 4111111111111111')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('CREDIT_CARD')
  })

  it('rejects an invalid card', () => {
    expect(luhnValid('4111111111111112')).toBe(false)
    expect(run(creditCardScanner(), '4111111111111112')).toHaveLength(0)
  })
})

describe('phoneScanner', () => {
  it('detects an E.164 number with reduced confidence', () => {
    const m = run(phoneScanner(), 'call +31 20 123 4567 now')
    expect(m.length).toBeGreaterThanOrEqual(1)
    expect(m[0]!.entityType).toBe('PHONE')
    expect(m[0]!.confidence).toBeCloseTo(0.85)
  })

  it('respects supportsLocale', () => {
    const s = phoneScanner({ locales: ['NL'] })
    expect(s.supportsLocale!('nl')).toBe(true)
    expect(s.supportsLocale!('US')).toBe(false)
  })
})

describe('ipAddressScanner', () => {
  it('detects valid IPv4', () => {
    const m = run(ipAddressScanner(), 'host 192.168.1.1 down')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('IP_ADDRESS')
  })

  it('detects compressed IPv6 with ::', () => {
    const m = run(ipAddressScanner(), 'addr 2001:db8::1 here')
    expect(m).toHaveLength(1)
    expect(m[0]!.value).toBe('2001:db8::1')
  })

  it('rejects an out-of-range IPv4', () => {
    expect(run(ipAddressScanner(), '999.999.999.999')).toHaveLength(0)
  })
})

describe('ssnScanner', () => {
  it('detects a US SSN with separators', () => {
    const m = run(ssnScanner(), 'SSN 123-45-6789')
    expect(m).toHaveLength(1)
    expect(m[0]!.entityType).toBe('SSN')
  })

  it('does not match a bare 9-digit number', () => {
    expect(run(ssnScanner(), '123456789')).toHaveLength(0)
  })
})

describe('secretScanner', () => {
  it('detects an Anthropic key', () => {
    const m = run(secretScanner(), 'key sk-ant-abcdefghijklmnopqrstuvwx')
    expect(m.length).toBeGreaterThanOrEqual(1)
    expect(m[0]!.entityType).toBe('SECRET')
  })

  it('detects an AWS access key and a JWT', () => {
    const aws = run(secretScanner(), 'AKIAIOSFODNN7EXAMPLE')
    expect(aws.length).toBeGreaterThanOrEqual(1)
    const jwt = run(
      secretScanner(),
      'token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcDEF123',
    )
    expect(jwt.length).toBeGreaterThanOrEqual(1)
  })

  it('detects a DB connection string', () => {
    const m = run(secretScanner(), 'postgres://user:pass@host:5432/db')
    expect(m.length).toBeGreaterThanOrEqual(1)
  })
})
