import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { buildAdapter } from '../../src/providers/index.js'
import { geminiAdapter, geminiToContents } from '../../src/providers/gemini.js'
import { azureOpenaiAdapter } from '../../src/providers/azure-openai.js'
import { ollamaAdapter } from '../../src/providers/ollama.js'
import { GavioOpenAI } from '../../src/shim/openai.js'

describe('v0.2.0 providers', () => {
  it('registry resolves gemini/azure/ollama', () => {
    expect(buildAdapter('gemini').providerName).toBe('gemini')
    expect(buildAdapter('azure_openai').providerName).toBe('azure_openai')
    expect(buildAdapter('ollama').providerName).toBe('ollama')
  })

  it('gemini maps roles and extracts system', () => {
    const { system, contents } = geminiToContents([
      { role: 'system', content: 'be terse' },
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: 'hello' },
    ])
    expect(system).toBe('be terse')
    expect(contents[0]!.role).toBe('user')
    expect(contents[1]!.role).toBe('model')
  })

  it('azure builds the deployment url', () => {
    const a = azureOpenaiAdapter({
      apiKey: 'k',
      endpoint: 'https://my.openai.azure.com/',
      deployment: 'gpt4o',
      apiVersion: '2024-06-01',
    })
    const url = a.url({ model: 'gpt-4o' } as never)
    expect(url).toBe(
      'https://my.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-06-01',
    )
  })

  it('health checks', async () => {
    expect(await geminiAdapter({ apiKey: 'k' }).healthCheck()).toBe(true)
    expect(await geminiAdapter({}).healthCheck()).toBe(false)
    expect(await azureOpenaiAdapter({ apiKey: 'k', endpoint: 'https://x' }).healthCheck()).toBe(true)
    expect(await ollamaAdapter().healthCheck()).toBe(true)
  })
})

describe('OpenAI shim (F-DX-04)', () => {
  it('returns an OpenAI-shaped completion', async () => {
    const client = new GavioOpenAI(new Gateway({ devMode: true }))
    const resp = await client.chat.completions.create({
      model: 'mock',
      messages: [{ role: 'user', content: 'hi there' }],
    })
    expect(resp.choices[0]!.message.role).toBe('assistant')
    expect(resp.choices[0]!.message.content).toContain('hi there')
    expect(resp.object).toBe('chat.completion')
    expect(resp.gavio.cacheHit).toBe(false)
  })
})

describe('config loader (F-DX-05)', () => {
  it('builds from a config object', async () => {
    const gw = await Gateway.fromConfig({
      devMode: true,
      interceptors: {
        pii_guard: { enabled: true, sensitivity: 'strict' },
        retry: { enabled: true, max_attempts: 2 },
        audit: { enabled: true, sink: 'stdout' },
      },
    })
    const r = await gw.complete({ messages: [{ role: 'user', content: 'mail jan@example.com' }] })
    expect(r.interceptorsFired).toContain('pii_guard')
    expect(r.interceptorsFired).toContain('retry')
    expect(r.interceptorsFired).toContain('audit')
  })

  it('skips disabled interceptors', async () => {
    const gw = await Gateway.fromConfig({
      devMode: true,
      interceptors: { pii_guard: { enabled: false } },
    })
    const r = await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })
    expect(r.interceptorsFired).not.toContain('pii_guard')
  })
})
