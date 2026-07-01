import { describe, it, expect } from 'vitest'
import { uuid7, newTraceId } from '../../src/ids.js'

describe('uuid7', () => {
  it('produces a version-7 variant-2 UUID', () => {
    const id = uuid7()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    // version nibble (13th hex char) must be '7'
    expect(id[14]).toBe('7')
    // variant high bits: first nibble of the 4th group must be 8,9,a,b
    expect('89ab').toContain(id[19])
  })

  it('is monotonically non-decreasing across many calls', () => {
    const ids: string[] = []
    for (let i = 0; i < 5000; i++) ids.push(uuid7())
    for (let i = 1; i < ids.length; i++) {
      expect(ids[i]! >= ids[i - 1]!).toBe(true)
    }
  })

  it('produces unique ids', () => {
    const ids = new Set<string>()
    for (let i = 0; i < 5000; i++) ids.add(uuid7())
    expect(ids.size).toBe(5000)
  })

  it('newTraceId returns a uuid string', () => {
    expect(newTraceId()).toMatch(/^[0-9a-f-]{36}$/)
  })
})
