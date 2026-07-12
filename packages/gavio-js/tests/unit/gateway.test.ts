import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { ConfigurationError } from '../../src/errors.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import { ibanScanner } from '../../src/interceptors/pii/scanners/index.js'
import { auditInterceptor } from '../../src/interceptors/audit/index.js'
import { retryInterceptor } from '../../src/interceptors/reliability/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import { ServerError } from '../../src/errors.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import type { AuditRecord } from '../../src/interceptors/audit/record.js'
import type { Interceptor } from '../../src/interceptors/base.js'

describe('Gateway dev mode roundtrip', () => {
  it('auto-wires mock provider + audit and restores PII', async () => {
    const captured: AuditRecord[] = []
    const sink: AuditSink = { async write(r) { captured.push(r) } }
    const gw = new Gateway({ devMode: true })
      .use(auditInterceptor({ sink }))
      .use(
        piiGuard({
          scanners: [ibanScanner()],
          logEntityTypes: false,
        }),
      )
      .withAdapter(mockProvider({ response: 'done [IBAN_1]' }))

    const res = await gw.complete({
      messages: [{ role: 'user', content: 'pay NL91ABNA0417164300' }],
      agentId: 'billing',
    })

    expect(res.content).toContain('NL91ABNA0417164300')
    expect(res.audit).not.toBeNull()
    expect(res.audit!.piiEntityTypes).toContain('IBAN')
    expect(captured).toHaveLength(1)
    expect(res.interceptorsFired).toContain('pii_guard')
    expect(res.provider).toBe('mock')
  })

  it('dev mode injects a default audit interceptor', async () => {
    const lines: string[] = []
    const sink: AuditSink = { async write() {} }
    // use our own sink to avoid stdout noise but still exercise default-wiring path
    const gw = new Gateway({ devMode: true }).withAdapter(mockProvider({ response: 'hi' }))
    void sink
    void lines
    const res = await gw.complete({ messages: [{ role: 'user', content: 'hello' }] })
    expect(res.audit).not.toBeNull()
    expect(res.audit!.provider).toBe('mock')
  })
})

describe('Gateway configuration', () => {
  it('throws when no provider is configured', () => {
    const gw = new Gateway()
    expect(() => gw.providerName).toThrow(ConfigurationError)
  })

  it('uses default model for the provider', () => {
    const gw = new Gateway({ provider: 'anthropic' })
    expect(gw.model).toBe('claude-sonnet-4-6')
  })
})

describe('Gateway executor policies', () => {
  it('composes a retry policy around the provider call', async () => {
    let calls = 0
    const flaky = mockProvider({ response: 'ok' })
    const orig = flaky.complete.bind(flaky)
    flaky.complete = async (request) => {
      calls += 1
      if (calls < 2) throw new ServerError('5xx')
      return orig(request)
    }
    const gw = new Gateway()
      .withAdapter(flaky)
      .use(retryInterceptor({ maxAttempts: 3, jitter: false, sleep: async () => {} }))
    const res = await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })
    expect(calls).toBe(2)
    expect(res.content).toBe('ok')
    expect(res.interceptorsFired).toContain('retry')
  })
})

describe('Gateway dry-run', () => {
  it('does not redact in dry-run mode but still completes', async () => {
    const gw = new Gateway({ dryRun: true })
      .withAdapter(mockProvider())
      .use(piiGuard({ scanners: [ibanScanner()], logEntityTypes: false }))
    const res = await gw.complete({
      messages: [{ role: 'user', content: 'pay NL91ABNA0417164300' }],
    })
    // mock echoes the (un-redacted) prompt back
    expect(res.content).toContain('NL91ABNA0417164300')
  })
})

describe('Gateway runtime context', () => {
  it('derives first-class runtime fields from request metadata', async () => {
    let captured: Parameters<NonNullable<Interceptor['before']>>[1] | null = null
    const capture: Interceptor = {
      name: 'runtime_capture',
      before(request, ctx) {
        captured = ctx
        return request
      },
    }
    const gw = new Gateway({ devMode: true }).use(capture)
    await gw.complete({
      messages: [{ role: 'user', content: 'hi' }],
      metadata: {
        tenant: 'acme',
        feature: 'support',
        costDimensions: { workflow: 'triage' },
        retry: { attempt: 1 },
        tools: { allowed: ['search'] },
        policy: { pack: 'fintech' },
      },
    })

    expect(captured).not.toBeNull()
    expect(captured!.tenant).toBe('acme')
    expect(captured!.feature).toBe('support')
    expect(captured!.cost['tenant']).toBe('acme')
    expect(captured!.cost['feature']).toBe('support')
    expect((captured!.cost['dimensions'] as Record<string, unknown>)['workflow']).toBe('triage')
    expect(captured!.retry['attempt']).toBe(1)
    expect(captured!.tools['allowed']).toEqual(['search'])
    expect(captured!.policy['pack']).toBe('fintech')
  })
})
