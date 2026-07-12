import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  buildPlatformRuntimeProfile,
  verifyPlatformRuntimeProfile,
} from '../../src/platform-runtime.js'

const vector = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/platform-runtime/profile.json', import.meta.url)),
    'utf8',
  ),
) as {
  readyProfileInput: Parameters<typeof buildPlatformRuntimeProfile>[0]
  readyProfile: Record<string, unknown>
  gapCase: {
    input: Parameters<typeof buildPlatformRuntimeProfile>[0]
    expectedReadiness: Record<string, unknown>
  }
}

describe('platform runtime profile', () => {
  it('builds the shared platform runtime profile', () => {
    const profile = buildPlatformRuntimeProfile(vector.readyProfileInput)

    expect(profile).toEqual(vector.readyProfile)
    const result = verifyPlatformRuntimeProfile(profile)
    expect(result.valid).toBe(true)
    expect(result.errors).toEqual([])
    expect(result.computedHash).toBe(profile.profileHash)
  })

  it('reports platform runtime readiness gaps', () => {
    const profile = buildPlatformRuntimeProfile(vector.gapCase.input)

    expect(profile.readiness).toEqual(vector.gapCase.expectedReadiness)
    expect(profile.readiness.ready).toBe(false)
  })

  it('rejects tampered or content-bearing profiles', () => {
    const profile = buildPlatformRuntimeProfile(vector.readyProfileInput) as Record<string, unknown>
    ;(profile.runtime as Record<string, unknown>).eventExportMode = 'full_local_debug'
    ;(profile.runtime as Record<string, unknown>).rawPrompt = 'do not store me'

    const result = verifyPlatformRuntimeProfile(profile)

    expect(result.valid).toBe(false)
    expect(result.errors).toContain('profileHash does not match profile content')
    expect(result.errors).toContain('profile contains content-bearing keys')
    expect(result.errors).toContain('readiness does not match profile content')
  })
})
