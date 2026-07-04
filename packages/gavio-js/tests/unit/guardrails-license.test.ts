import { describe, it, expect } from 'vitest'
import {
  guardrails,
  licenseDetector,
  detectLicenses,
} from '../../src/interceptors/guardrails/index.js'
import { auditInterceptor } from '../../src/interceptors/audit/index.js'
import { GuardrailViolationError } from '../../src/errors.js'
import { GavioTestKit } from '../../src/testing/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import type { AuditRecord as Rec } from '../../src/interceptors/audit/record.js'

// Synthetic license snippets — fixtures only, mirror test-vectors/license.
const MIT =
  'Permission is hereby granted, free of charge, to any person obtaining a copy of ' +
  'this software and associated documentation files (the "Software"), to deal in the ' +
  'Software without restriction, including without limitation the rights to use, copy, ' +
  'modify, merge, publish, distribute, sublicense, and/or sell copies of the Software.'
const APACHE =
  'Licensed under the Apache License, Version 2.0 (the "License"); you may not use this ' +
  'file except in compliance with the License. You may obtain a copy of the License at.'
const GPL3 =
  'This program is free software: you can redistribute it and/or modify it under the terms ' +
  'of the GNU General Public License as published by the Free Software Foundation, either ' +
  'version 3 of the License, or (at your option) any later version.'
const GPL2 =
  'This program is free software; you can redistribute it and/or modify it under the terms ' +
  'of the GNU General Public License as published by the Free Software Foundation; either ' +
  'version 2 of the License, or (at your option) any later version.'
const CLEAN = 'function add(a, b) { return a + b } // sums two numbers used across the project'

describe('detectLicenses (F-QUA-10)', () => {
  it('detects each supported license from a snippet', () => {
    expect(detectLicenses(MIT)).toEqual(['MIT'])
    expect(detectLicenses(APACHE)).toEqual(['Apache-2.0'])
    expect(detectLicenses(GPL3)).toEqual(['GPL-3.0'])
    expect(detectLicenses(GPL2)).toEqual(['GPL-2.0'])
  })

  it('does not confuse GPL-2.0 with GPL-3.0', () => {
    expect(detectLicenses(GPL2)).not.toContain('GPL-3.0')
    expect(detectLicenses(GPL3)).not.toContain('GPL-2.0')
  })

  it('returns nothing for ordinary code or prose', () => {
    expect(detectLicenses(CLEAN)).toEqual([])
    expect(detectLicenses('the quick brown fox jumps over the lazy dog every morning')).toEqual([])
  })

  it('returns multiple sorted ids when several licenses appear', () => {
    expect(detectLicenses(`${MIT}\n\n${APACHE}`)).toEqual(['Apache-2.0', 'MIT'])
  })

  it('honours the licenses subset option', () => {
    expect(detectLicenses(MIT, { licenses: ['Apache-2.0'] })).toEqual([])
    expect(detectLicenses(MIT, { licenses: ['MIT'] })).toEqual(['MIT'])
  })
})

describe('licenseDetector validator', () => {
  it('is named license_detector', () => {
    expect(licenseDetector().name).toBe('license_detector')
  })

  it('passes clean content and fails on license text naming the ids', () => {
    expect(licenseDetector().validate(CLEAN).ok).toBe(true)
    const res = licenseDetector().validate(MIT)
    expect(res.ok).toBe(false)
    expect(res.reason).toBe('license text detected: MIT')
  })
})

describe('licenseDetector through the guardrails interceptor', () => {
  it('blocks a response carrying license text (onFailure=error)', async () => {
    const kit = new GavioTestKit({
      interceptors: [guardrails({ validators: [licenseDetector()] })],
      provider: mockProvider({ response: MIT }),
    })
    await expect(kit.run({ messages: [{ role: 'user', content: 'q' }] })).rejects.toBeInstanceOf(
      GuardrailViolationError,
    )
  })

  it('records the FAIL outcome in the audit record (onFailure=warn)', async () => {
    const captured: Rec[] = []
    const sink: AuditSink = {
      async write(r) {
        captured.push(r)
      },
    }
    const kit = new GavioTestKit({
      interceptors: [
        auditInterceptor({ sink }),
        guardrails({ validators: [licenseDetector()], onFailure: 'warn' }),
      ],
      provider: mockProvider({ response: APACHE }),
    })
    const result = await kit.run({ messages: [{ role: 'user', content: 'q' }] })
    expect(result.response.content).toBe(APACHE) // warned, not blocked
    expect(result.auditRecord?.guardrailOutcome).toBe('FAIL')
    expect(captured).toHaveLength(1)
    expect(captured[0]!.guardrailOutcome).toBe('FAIL')
  })

  it('passes clean content through with a PASS outcome', async () => {
    const captured: Rec[] = []
    const sink: AuditSink = {
      async write(r) {
        captured.push(r)
      },
    }
    const kit = new GavioTestKit({
      interceptors: [auditInterceptor({ sink }), guardrails({ validators: [licenseDetector()] })],
      provider: mockProvider({ response: CLEAN }),
    })
    const result = await kit.run({ messages: [{ role: 'user', content: 'q' }] })
    expect(result.auditRecord?.guardrailOutcome).toBe('PASS')
  })
})
