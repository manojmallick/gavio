import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { GavioRequest } from '../../src/request.js'
import { buildAdapter } from '../../src/providers/index.js'
import { geminiAdapter, geminiToContents } from '../../src/providers/gemini.js'
import { azureOpenaiAdapter } from '../../src/providers/azure-openai.js'
import { ollamaAdapter } from '../../src/providers/ollama.js'
import { openrouterAdapter } from '../../src/providers/openrouter.js'
import { GavioOpenAI } from '../../src/shim/openai.js'

describe('v0.2.0 providers', () => {
  it('registry resolves gemini/azure/ollama', () => {
    expect(buildAdapter('gemini').providerName).toBe('gemini')
    expect(buildAdapter('azure_openai').providerName).toBe('azure_openai')
    expect(buildAdapter('openrouter').providerName).toBe('openrouter')
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
    expect(await openrouterAdapter({ apiKey: 'k' }).healthCheck()).toBe(true)
    expect(await ollamaAdapter().healthCheck()).toBe(true)
  })

  it('openrouter builds url and attribution headers', () => {
    const adapter = openrouterAdapter({
      apiKey: 'k',
      baseUrl: 'https://router.example/v1/',
      httpReferer: 'https://app.example',
      appTitle: 'Gavio',
    })
    expect(adapter.url()).toBe('https://router.example/v1/chat/completions')
    expect(adapter.headers()['Authorization']).toBe('Bearer k')
    expect(adapter.headers()['HTTP-Referer']).toBe('https://app.example')
    expect(adapter.headers()['X-OpenRouter-Title']).toBe('Gavio')
    const gw = new Gateway({ provider: 'openrouter' })
    expect(gw.providerName).toBe('openrouter')
    expect(gw.model).toBe('openai/gpt-4o')
  })

  it('openrouter posts chat completions and preserves response metadata', async () => {
    const originalFetch = globalThis.fetch
    let capturedUrl = ''
    let capturedInit: RequestInit | undefined
    globalThis.fetch = (async (url, init) => {
      capturedUrl = String(url)
      capturedInit = init
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: 'ok' } }],
          usage: { prompt_tokens: 1000, completion_tokens: 500 },
          model: 'openai/gpt-4o',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }) as typeof fetch

    try {
      const adapter = openrouterAdapter({
        apiKey: 'k',
        httpReferer: 'https://app.example',
        appTitle: 'Gavio',
      })
      const response = await adapter.complete(
        new GavioRequest({
          messages: [{ role: 'user', content: 'hi' }],
          model: 'openai/gpt-4o',
          provider: 'openrouter',
        }),
      )

      const payload = JSON.parse(String(capturedInit!.body)) as Record<string, unknown>
      const headers = capturedInit!.headers as Record<string, string>
      expect(capturedUrl).toBe('https://openrouter.ai/api/v1/chat/completions')
      expect(payload['model']).toBe('openai/gpt-4o')
      expect(payload['messages']).toEqual([{ role: 'user', content: 'hi' }])
      expect(payload['max_tokens']).toBe(1024)
      expect(headers['Authorization']).toBe('Bearer k')
      expect(headers['HTTP-Referer']).toBe('https://app.example')
      expect(response.provider).toBe('openrouter')
      expect(response.model).toBe('openai/gpt-4o')
      expect(response.modelVersion).toBe('openai/gpt-4o')
      expect(response.costUsd).toBeGreaterThan(0)
    } finally {
      globalThis.fetch = originalFetch
    }
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
