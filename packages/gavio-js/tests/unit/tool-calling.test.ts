/**
 * Normalised tool calling (F-ADP-03): shared vectors, schemas, privacy, audit, cache, bridge.
 *
 * Runs the same `test-vectors/tool-calling/*.json` files the Python SDK runs. Messages in
 * the vectors use the spec's camelCase keys (`toolCalls`, `toolCallId`, `isError`), which is
 * also how this SDK spells them.
 */

import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ConfigurationError, MalformedToolCallError, PiiBlockedError } from '../../src/errors.js'
import { InterceptorContext } from '../../src/context.js'
import { Gateway } from '../../src/gateway.js'
import { auditInterceptor, verifyChain } from '../../src/interceptors/audit/index.js'
import { AuditRecord } from '../../src/interceptors/audit/record.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import { hashingEmbedder, semanticCache } from '../../src/interceptors/cache/index.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import {
  analyzeProviderToolCalls,
  analyzeToolRuntime,
  toolCallsToRuntimeCalls,
} from '../../src/interceptors/tool-runtime/index.js'
import * as anthropic from '../../src/providers/anthropic.js'
import * as azure from '../../src/providers/azure-openai.js'
import type { BaseProviderAdapter } from '../../src/providers/base.js'
import * as gemini from '../../src/providers/gemini.js'
import { mockProvider } from '../../src/providers/mock.js'
import * as ollama from '../../src/providers/ollama.js'
import * as openai from '../../src/providers/openai.js'
import * as openrouter from '../../src/providers/openrouter.js'
import { GavioRequest } from '../../src/request.js'
import type { GavioRequestInit } from '../../src/request.js'
import { GavioResponse } from '../../src/response.js'
import { canonicalJson } from '../../src/canonical-json.js'
import { dumpArguments } from '../../src/tool-calling.js'
import type { Wire } from '../../src/tool-calling.js'
import { FinishReason, PiiMode, TokenUsage, ToolCall, ToolResult } from '../../src/types.js'
import type { Message } from '../../src/types.js'

const VECTORS = new URL('../../../../test-vectors/tool-calling/', import.meta.url)
const SPEC = new URL('../../../../spec/', import.meta.url)
const IBAN = 'NL91ABNA0417164300' // synthetic, checksum-valid

interface WireModule {
  toProviderPayload: (request: GavioRequest) => unknown
  fromProviderResponse: (payload: Wire) => { toWire: () => unknown }
}

const FILES: Record<string, Array<[string, WireModule, (() => BaseProviderAdapter)]>> = {
  'openai.json': [
    ['openai', openai, () => openai.openaiAdapter({ apiKey: 'k' })],
    ['openrouter', openrouter, () => openrouter.openrouterAdapter({ apiKey: 'k' })],
  ],
  'azure.json': [
    [
      'azure_openai',
      azure,
      () => azure.azureOpenaiAdapter({ apiKey: 'k', endpoint: 'https://x.openai.azure.com' }),
    ],
  ],
  'anthropic.json': [['anthropic', anthropic, () => anthropic.anthropicAdapter({ apiKey: 'k' })]],
  'gemini.json': [['gemini', gemini, () => gemini.geminiAdapter({ apiKey: 'k' })]],
  'ollama.json': [['ollama', ollama, () => ollama.ollamaAdapter()]],
}

const ERRORS = { MalformedToolCall: MalformedToolCallError, Configuration: ConfigurationError }

type Doc = Record<string, any> // eslint-disable-line @typescript-eslint/no-explicit-any

function doc(file: string): Doc {
  return JSON.parse(readFileSync(fileURLToPath(new URL(file, VECTORS)), 'utf8'))
}

/** Build a GavioRequest from a spec-shaped (camelCase) vector request. */
function vectorRequest(data: Doc): GavioRequest {
  const options = data['options'] ?? {}
  return new GavioRequest({
    messages: data['messages'],
    model: data['model'],
    provider: data['provider'],
    options: { temperature: options['temperature'], maxTokens: options['maxTokens'] },
    tools: data['tools'],
    toolChoice: data['toolChoice'],
  })
}

function request(init: Partial<GavioRequestInit> & { messages: Message[] }): GavioRequest {
  return new GavioRequest({ model: 'm', provider: 'mock', ...init })
}

const history = (args: Record<string, unknown>, toolContent = 'ok'): GavioRequest =>
  request({
    messages: [
      { role: 'user', content: 'Pay the invoice' },
      {
        role: 'assistant',
        content: '',
        toolCalls: [{ id: 'call_1', name: 'transfer', arguments: args }],
      },
      { role: 'tool', toolCallId: 'call_1', content: toolContent },
    ],
    tools: [
      {
        name: 'transfer',
        description: 'Send money',
        inputSchema: { type: 'object', properties: { iban: { type: 'string' } } },
      },
    ],
  })

// ---- shared vectors -----------------------------------------------------------------

for (const [file, modules] of Object.entries(FILES)) {
  const vectors = doc(file)
  for (const [name, mod] of modules) {
    describe(`tool-calling vectors — ${name} (${file})`, () => {
      for (const c of vectors['requestCases'] ?? []) {
        it(`request: ${c.id}`, () => {
          expect(mod.toProviderPayload(vectorRequest(c.request))).toStrictEqual(c.expectedPayload)
        })
      }
      for (const c of vectors['requestErrorCases'] ?? []) {
        it(`request error: ${c.id}`, () => {
          const ErrorClass = ERRORS[c.expectedError as keyof typeof ERRORS]
          expect(() => mod.toProviderPayload(vectorRequest(c.request))).toThrow(ErrorClass)
        })
      }
      for (const c of vectors['responseCases'] ?? []) {
        it(`response: ${c.id}`, () => {
          expect(mod.fromProviderResponse(c.providerResponse).toWire()).toStrictEqual(c.expected)
        })
      }
      for (const c of vectors['errorCases'] ?? []) {
        it(`malformed response: ${c.id} (no argument leak)`, () => {
          const ErrorClass = ERRORS[c.expectedError as keyof typeof ERRORS]
          let thrown: unknown
          try {
            mod.fromProviderResponse(c.providerResponse)
          } catch (error) {
            thrown = error
          }
          expect(thrown).toBeInstanceOf(ErrorClass)
          // The message names the tool at most — never the arguments, which may hold PII.
          const message = (thrown as Error).message
          expect(message).not.toContain('{"city"')
          expect(message).not.toContain('Paris')
        })
      }
    })
  }
}

describe('tool-calling vectors — arguments hash', () => {
  const cases: Doc[] = doc('arguments-hash.json')['cases']
  for (const c of cases) {
    it(c.id, () => {
      const call = new ToolCall({ id: 'c', name: 't', arguments: c.arguments })
      expect(canonicalJson(call.arguments)).toBe(c.expectedCanonical)
      expect(call.argumentsHash()).toBe(c.expectedHash)
    })
  }

  it('ignores key order', () => {
    const byId = Object.fromEntries(cases.map((c) => [c.id, c]))
    expect(byId['key-order-does-not-matter-a'].expectedHash).toBe(
      byId['key-order-does-not-matter-b'].expectedHash,
    )
  })

  it('orders keys by Unicode code point, not UTF-16 code unit', () => {
    // U+FF5E sorts before U+1F600 by code point; by UTF-16 unit the surrogate (0xD83D) is first.
    const call = new ToolCall({ id: 'c', name: 't', arguments: { '\u{1F600}': 1, '～': 2 } })
    expect(canonicalJson(call.arguments)).toBe('{"～":2,"\u{1F600}":1}')
  })
})

// ---- complete() wiring --------------------------------------------------------------

describe('adapter complete() returns tool calls', () => {
  afterEach(() => vi.unstubAllGlobals())

  for (const [file, modules] of Object.entries(FILES)) {
    for (const [name, , factory] of modules) {
      it(name, async () => {
        const vectors = doc(file)
        const requestCase = vectors['requestCases'].find((c: Doc) => c.id === 'declares-tools-auto')
        const responseCase = vectors['responseCases'].find(
          (c: Doc) => c.id.includes('call') && c.expected.toolCalls.length > 0,
        )
        const sent: { body?: unknown } = {}
        vi.stubGlobal(
          'fetch',
          vi.fn(async (_url: string, init: { body: string }) => {
            sent.body = JSON.parse(init.body)
            return new Response(JSON.stringify(responseCase.providerResponse), { status: 200 })
          }),
        )

        const response = await factory().complete(vectorRequest(requestCase.request))

        expect(sent.body).toStrictEqual(requestCase.expectedPayload)
        const expected = responseCase.expected
        expect(response.toolCalls.map((c) => c.toJSON())).toStrictEqual(expected.toolCalls)
        expect(response.finishReason).toBe(expected.finishReason)
        expect(response.content).toBe(expected.content)
        expect(response.usage.promptTokens).toBe(expected.usage.promptTokens)
        expect(response.modelVersion).toBe(expected.modelVersion ?? requestCase.request.model)
      })
    }
  }
})

// ---- schemas ------------------------------------------------------------------------

function schema(name: string): Doc {
  return JSON.parse(readFileSync(fileURLToPath(new URL(`${name}.schema.json`, SPEC)), 'utf8'))
}

describe('spec conformance', () => {
  it('ToolCall.toJSON() carries exactly the spec properties', () => {
    const spec = schema('ToolCall')
    const json = new ToolCall({ id: 'c1', name: 't', arguments: { a: 1 } }).toJSON()
    expect(Object.keys(json).sort()).toEqual(Object.keys(spec['properties']).sort())
    expect(spec['required']).toEqual(['id', 'name', 'arguments'])
    expect(spec['additionalProperties']).toBe(false)
  })

  it('ToolResult.toMessage() uses the spec spelling for every key it writes', () => {
    const spec = schema('ToolResult')
    const message = new ToolCall({ id: 'c1', name: 't' }).result('boom', { isError: true }).toMessage()
    const { role, ...rest } = message
    expect(role).toBe('tool')
    for (const key of Object.keys(rest)) expect(Object.keys(spec['properties'])).toContain(key)
    expect(spec['required']).toEqual(['toolCallId', 'content'])
  })

  it('the SDK adds no tool-calling key the request/response/audit schemas do not define', () => {
    expect(Object.keys(schema('GavioRequest')['properties'])).toEqual(
      expect.arrayContaining(['tools', 'toolChoice']),
    )
    expect(Object.keys(schema('GavioResponse')['properties'])).toEqual(
      expect.arrayContaining(['toolCalls', 'finishReason']),
    )
    const auditCalls = schema('AuditRecord')['properties']['toolCalls']['items']
    expect(auditCalls['required']).toEqual(['name', 'argumentsHash'])
    expect(auditCalls['additionalProperties']).toBe(false)
  })

  it('finishReason values match the response schema enum', () => {
    const allowed = schema('GavioResponse')['properties']['finishReason']['enum'].filter(
      (v: unknown) => v !== null,
    )
    expect(Object.values(FinishReason).sort()).toEqual([...allowed].sort())
  })

  it('every tool-calling schema file referenced by the spec index exists', () => {
    const files = readdirSync(fileURLToPath(SPEC))
    expect(files).toEqual(expect.arrayContaining(['ToolCall.schema.json', 'ToolResult.schema.json']))
  })
})

// ---- types --------------------------------------------------------------------------

describe('ToolCall / ToolResult', () => {
  it('a tool result round-trips through a message', () => {
    const call = new ToolCall({ id: 'c1', name: 'get_weather', arguments: { city: 'Paris' } })
    expect(call.result('18C').toMessage()).toStrictEqual({
      role: 'tool',
      toolCallId: 'c1',
      content: '18C',
      name: 'get_weather',
    })
    const failed = call.result('boom', { isError: true })
    expect(failed).toEqual(
      new ToolResult({ toolCallId: 'c1', content: 'boom', name: 'get_weather', isError: true }),
    )
    expect(failed.toMessage()['isError']).toBe(true)
  })

  it('ToolCall.from validates', () => {
    expect(ToolCall.from({ id: 'c', name: 't' })).toEqual(new ToolCall({ id: 'c', name: 't' }))
    expect(() => ToolCall.from({ id: 'c', name: 't', arguments: '{}' })).toThrow(TypeError)
    expect(() => ToolCall.from('not an object')).toThrow(TypeError)
    expect(() => ToolCall.from({ name: 't' })).toThrow(TypeError)
    const call = new ToolCall({ id: 'c', name: 't' })
    expect(ToolCall.from(call)).toBe(call)
  })

  it('toJSON returns a copy of the arguments', () => {
    const call = new ToolCall({ id: 'c', name: 't', arguments: { a: { b: 1 } } })
    const json = call.toJSON()
    ;(json.arguments['a'] as { b: number }).b = 2
    expect((call.arguments['a'] as { b: number }).b).toBe(1)
  })

  it('a response feeds the next request through toAssistantMessage', () => {
    const call = new ToolCall({ id: 'call_1', name: 'get_weather', arguments: { city: 'Paris' } })
    const response = new GavioResponse({
      traceId: 't',
      content: '',
      model: 'm',
      provider: 'openai',
      toolCalls: [call],
    })
    const messages: Message[] = [
      { role: 'user', content: 'weather?' },
      response.toAssistantMessage(),
      call.result('{"t":18}').toMessage(),
    ]
    const payload = openai.toProviderPayload(
      new GavioRequest({ messages, model: 'gpt-4o', provider: 'openai' }),
    ) as { messages: Array<Record<string, any>> } // eslint-disable-line @typescript-eslint/no-explicit-any
    expect(payload.messages[1]!['tool_calls'][0].function.arguments).toBe('{"city":"Paris"}')
    expect(payload.messages[2]).toStrictEqual({
      role: 'tool',
      tool_call_id: 'call_1',
      content: '{"t":18}',
    })
    const plain = new GavioResponse({ traceId: 't', content: 'hi', model: 'm', provider: 'openai' })
    expect(plain.toAssistantMessage()).toStrictEqual({ role: 'assistant', content: 'hi' })
  })

  it('snake_case-spelled messages are accepted on read', () => {
    const wire: Message[] = [
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: '', toolCalls: [{ id: 'c1', name: 't', arguments: {} }] },
      { role: 'tool', toolCallId: 'c1', isError: true, content: 'x' },
    ]
    const snake: Message[] = [
      wire[0]!,
      { role: 'assistant', content: '', tool_calls: wire[1]!['toolCalls'] },
      { role: 'tool', tool_call_id: 'c1', is_error: true, content: 'x' },
    ]
    for (const mod of [openai, anthropic, gemini, ollama]) {
      expect(mod.toProviderPayload(request({ messages: wire }))).toStrictEqual(
        mod.toProviderPayload(request({ messages: snake })),
      )
    }
  })
})

describe('GavioRequest / GavioResponse', () => {
  it('promptText includes tool calls but is unchanged without them', () => {
    const plain = request({
      messages: [
        { role: 'user', content: 'a' },
        { role: 'assistant', content: 'b' },
      ],
    })
    expect(plain.promptText()).toBe('a\nb')
    const withCalls = request({
      messages: [
        { role: 'user', content: 'a' },
        {
          role: 'assistant',
          content: '',
          toolCalls: [{ id: 'c', name: 't', arguments: { k: 'v' } }],
        },
      ],
    })
    expect(withCalls.promptText()).toBe('a\n\nt({"k":"v"})')
  })

  it('copyWithMessages carries every request field', () => {
    const original = new GavioRequest({
      messages: [{ role: 'user', content: 'hi' }],
      model: 'm',
      provider: 'openai',
      agentId: 'a',
      parentTraceId: 'p',
      sessionId: 's',
      options: { temperature: 0.1 },
      metadata: { k: 1 },
      images: [new Uint8Array([1])],
      tools: [{ name: 't' }],
      toolChoice: { name: 't' },
    })
    const copy = original.copyWithMessages([{ role: 'user', content: 'changed' }])
    for (const key of Object.keys(original) as Array<keyof GavioRequest>) {
      if (key === 'messages') continue
      expect(copy[key], key).toEqual(original[key])
    }
    expect(copy.tools).not.toBe(original.tools)
    expect(copy.options).not.toBe(original.options)
    expect(copy.messages[0]!.content).toBe('changed')
  })

  it('copyWithContent carries every response field', () => {
    const original = new GavioResponse({
      traceId: 't',
      content: 'old',
      model: 'm',
      provider: 'openai',
      modelVersion: 'v',
      usage: new TokenUsage(1, 2),
      costUsd: 0.5,
      latencyMs: 7,
      cacheHit: true,
      interceptorsFired: ['a'],
      metadata: { k: 1 },
      embeddings: [[0.1]],
      toolCalls: [new ToolCall({ id: 'c', name: 't', arguments: { a: 1 } })],
      finishReason: FinishReason.TOOL_CALLS,
    })
    const copy = original.copyWithContent('new')
    for (const key of Object.keys(original) as Array<keyof GavioResponse>) {
      if (key === 'content') continue
      expect(copy[key], key).toEqual(original[key])
    }
    expect(copy.content).toBe('new')
    expect(copy.toolCalls).not.toBe(original.toolCalls)
  })
})

// ---- unit behaviour of the shared helpers -------------------------------------------

describe('request translation', () => {
  it('toolChoice without tools is not sent', () => {
    const r = request({ messages: [{ role: 'user', content: 'hi' }], toolChoice: 'required' })
    for (const mod of [openai, anthropic, gemini, ollama]) {
      const keys = Object.keys(mod.toProviderPayload(r) as object)
      for (const key of ['tools', 'tool_choice', 'toolConfig']) expect(keys).not.toContain(key)
    }
  })

  it('invalid toolChoice and declarations are rejected', () => {
    const messages: Message[] = [{ role: 'user', content: 'hi' }]
    const tools = [{ name: 't' }]
    for (const init of [
      { tools, toolChoice: 'sometimes' as never },
      { tools, toolChoice: { x: 1 } as never },
      { tools: [{ description: 'no name' } as never] },
    ]) {
      expect(() => openai.toProviderPayload(request({ messages, ...init }))).toThrow(
        ConfigurationError,
      )
    }
  })

  it('snake_case input_schema is accepted and governance fields are not sent', () => {
    const r = request({
      messages: [{ role: 'user', content: 'hi' }],
      tools: [
        {
          name: 't',
          input_schema: { type: 'object', properties: { a: { type: 'string' } } },
          permissions: ['x'],
          risk: 'destructive',
          requires_approval: true,
        },
      ],
    })
    const payload = openai.toProviderPayload(r) as { tools: Array<Record<string, unknown>> }
    expect(payload.tools[0]!['function']).toStrictEqual({
      name: 't',
      parameters: { type: 'object', properties: { a: { type: 'string' } } },
    })
  })

  it('arguments serialisation is compact and keeps insertion order', () => {
    const payload = openai.toProviderPayload(history({ z: 1, a: { y: 'é', b: [1, 2] } })) as {
      messages: Array<Record<string, any>> // eslint-disable-line @typescript-eslint/no-explicit-any
    }
    expect(payload.messages[1]!['tool_calls'][0].function.arguments).toBe(
      '{"z":1,"a":{"y":"é","b":[1,2]}}',
    )
    expect(dumpArguments({ z: 1, a: 2 })).toBe('{"z":1,"a":2}')
  })

  it('an OpenAI assistant turn with only tool calls sends null content', () => {
    const payload = openai.toProviderPayload(history({ iban: 'x' })) as {
      messages: Array<Record<string, unknown>>
    }
    expect(payload.messages[1]!['content']).toBeNull()
  })

  it('null content does not break OpenAI-style histories', () => {
    const r = request({
      messages: [
        { role: 'user', content: 'hi' },
        {
          role: 'assistant',
          content: null as never,
          toolCalls: [{ id: 'c', name: 't', arguments: {} }],
        },
        { role: 'tool', toolCallId: 'c', content: 'ok' },
      ],
    })
    expect(r.promptText()).toBe('hi\n\nt({})\nok')
    for (const mod of [openai, anthropic, gemini, ollama]) mod.toProviderPayload(r)
  })
})

// ---- privacy ------------------------------------------------------------------------

const newCtx = (init: { dryRun?: boolean } = {}): InterceptorContext =>
  new InterceptorContext({ traceId: 't', ...init })

const guard = (options: Parameters<typeof piiGuard>[0] = {}): Interceptor =>
  piiGuard({ logEntityTypes: false, ...options })

/** Messages of a request as loosely-typed objects, for reading tool-call keys back. */
const messagesOf = (r: GavioRequest): Array<Record<string, any>> => r.messages // eslint-disable-line @typescript-eslint/no-explicit-any

describe('PiiGuard covers tool calling', () => {
  const modules: Array<[string, WireModule]> = [
    ['openai', openai],
    ['azure_openai', azure],
    ['anthropic', anthropic],
    ['gemini', gemini],
    ['ollama', ollama],
  ]

  for (const [name, mod] of modules) {
    it(`an IBAN in a tool argument is redacted before the ${name} payload is built`, async () => {
      const original = history({ iban: IBAN, memo: `to ${IBAN} please`, nested: { l: [IBAN] } })
      const ctx = newCtx()
      const redacted = await guard().before!(original, ctx)
      const payload = JSON.stringify(mod.toProviderPayload(redacted))

      expect(payload).not.toContain(IBAN)
      expect(payload).toContain('[IBAN_')
      expect(ctx.piiEntityTypes).toContain('IBAN')
      // The caller's request object is untouched.
      expect(messagesOf(original)[1]!['toolCalls'][0].arguments.iban).toBe(IBAN)
    })
  }

  it('tool-role message content is scanned', async () => {
    const redacted = await guard().before!(
      history({ iban: 'x' }, 'sent to jan@example.com'),
      newCtx(),
    )
    expect(redacted.messages[2]!.content).toBe('sent to [EMAIL_1]')
  })

  it('a secret and a numeric identifier in tool arguments are redacted', async () => {
    const key = 'sk-ant-' + 'a1B2c3D4e5F6g7H8i9J0k1L2' // synthetic, matches the key pattern
    const redacted = await guard().before!(
      history({ api_key: key, bsn: 111222333, count: 3, flag: true, none: null }),
      newCtx(),
    )
    const args = messagesOf(redacted)[1]!['toolCalls'][0].arguments
    expect(JSON.stringify(args)).not.toContain(key)
    expect(args.bsn).toBe('[BSN_1]')
    expect([args.count, args.flag, args.none]).toEqual([3, true, null])
  })

  it('snake_case-spelled tool calls are scanned too', async () => {
    const r = request({
      messages: [
        { role: 'user', content: 'hi' },
        {
          role: 'assistant',
          content: '',
          tool_calls: [{ id: 'c', name: 't', arguments: { iban: IBAN } }],
        },
      ],
    })
    const redacted = await guard().before!(r, newCtx())
    expect(JSON.stringify(redacted.messages)).not.toContain(IBAN)
    expect(messagesOf(redacted)[1]!['tool_calls']).toBeDefined()
  })

  it('block mode refuses PII in tool arguments', async () => {
    await expect(
      guard({ mode: PiiMode.BLOCK }).before!(history({ iban: IBAN }), newCtx()),
    ).rejects.toBeInstanceOf(PiiBlockedError)
  })

  it('dry run reports but does not rewrite tool arguments', async () => {
    const original = history({ iban: IBAN })
    const ctx = newCtx({ dryRun: true })
    const result = await guard().before!(original, ctx)
    expect(result).toBe(original)
    expect(ctx.piiEntityTypes).toContain('IBAN')
  })

  it('placeholders are restored in response tool-call arguments', async () => {
    const g = guard()
    const ctx = newCtx()
    await g.before!(history({ iban: IBAN }), ctx)
    const response = new GavioResponse({
      traceId: 't',
      content: 'Sending to [IBAN_1]',
      model: 'm',
      provider: 'mock',
      toolCalls: [
        new ToolCall({ id: 'call_2', name: 'transfer', arguments: { iban: '[IBAN_1]', amount: 5 } }),
      ],
      finishReason: FinishReason.TOOL_CALLS,
    })
    const restored = await g.after!(response, ctx)
    expect(restored.content).toBe(`Sending to ${IBAN}`)
    expect(restored.toolCalls).toEqual([
      new ToolCall({ id: 'call_2', name: 'transfer', arguments: { iban: IBAN, amount: 5 } }),
    ])
    expect(restored.finishReason).toBe(FinishReason.TOOL_CALLS)
    // The original response is not mutated.
    expect(response.toolCalls[0]!.arguments['iban']).toBe('[IBAN_1]')
  })

  it('restores arguments even when the content has nothing to restore', async () => {
    const g = guard()
    const ctx = newCtx()
    await g.before!(history({ iban: IBAN }), ctx)
    const response = new GavioResponse({
      traceId: 't',
      content: '',
      model: 'm',
      provider: 'mock',
      toolCalls: [new ToolCall({ id: 'c', name: 'transfer', arguments: { iban: '[IBAN_1]' } })],
    })
    const restored = await g.after!(response, ctx)
    expect(restored.toolCalls[0]!.arguments['iban']).toBe(IBAN)
  })

  it('mask mode does not restore tool arguments', async () => {
    const g = guard({ mode: PiiMode.MASK })
    const ctx = newCtx()
    await g.before!(history({ iban: IBAN }), ctx)
    const response = new GavioResponse({
      traceId: 't',
      content: '',
      model: 'm',
      provider: 'mock',
      toolCalls: [new ToolCall({ id: 'c', name: 'transfer', arguments: { iban: '*'.repeat(18) } })],
    })
    expect(await g.after!(response, ctx)).toBe(response)
  })

  it('null content survives the PII guard', async () => {
    const r = request({
      messages: [
        { role: 'user', content: 'hi' },
        {
          role: 'assistant',
          content: null as never,
          toolCalls: [{ id: 'c', name: 't', arguments: {} }],
        },
      ],
    })
    const redacted = await guard().before!(r, newCtx())
    expect(redacted.messages[1]!.content).toBeNull()
  })
})

// ---- gateway end to end -------------------------------------------------------------

/** A mock provider that records what it was sent and answers with tool calls. */
function toolCallingProvider(toolCalls: ToolCall[], text = '') {
  const base = mockProvider({ response: text })
  const state = { seen: [] as GavioRequest[], calls: 0 }
  const adapter = {
    providerName: 'mock',
    async complete(r: GavioRequest): Promise<GavioResponse> {
      state.calls += 1
      state.seen.push(r)
      const response = await base.complete(r)
      response.toolCalls = [...toolCalls]
      response.finishReason = FinishReason.TOOL_CALLS
      return response
    },
    async healthCheck(): Promise<boolean> {
      return true
    },
  }
  return { adapter, state }
}

class CapturingSink implements AuditSink {
  readonly records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

describe('Gateway with tools', () => {
  it('redacts tool arguments, restores them and audits hashes only', async () => {
    const { adapter, state } = toolCallingProvider([
      new ToolCall({ id: 'call_2', name: 'transfer', arguments: { iban: '[IBAN_1]' } }),
    ])
    const gateway = new Gateway({ adapter, model: 'mock' })
      .use(auditInterceptor({ sink: new CapturingSink() }))
      .use(guard())
    const h = history({ iban: IBAN })

    const response = await gateway.complete({
      messages: h.messages,
      tools: h.tools,
      toolChoice: 'auto',
    })

    const sent = state.seen[0]!
    expect(sent.tools).toEqual(h.tools)
    expect(sent.toolChoice).toBe('auto')
    expect(JSON.stringify(openai.toProviderPayload(sent))).not.toContain(IBAN)
    // The caller sees the real value again...
    expect(response.toolCalls).toEqual([
      new ToolCall({ id: 'call_2', name: 'transfer', arguments: { iban: IBAN } }),
    ])
    // ...but the audit record keeps only the tool name and the arguments hash.
    expect(response.audit).not.toBeNull()
    expect(response.audit!.toolCalls).toEqual([
      { name: 'transfer', argumentsHash: response.toolCalls[0]!.argumentsHash() },
    ])
    const auditJson = JSON.stringify(response.audit)
    expect(auditJson).not.toContain(IBAN)
    expect(auditJson).not.toContain('[IBAN_1]')
    expect(auditJson).not.toContain('"arguments"')
  })
})

describe('Gateway.stream with tools', () => {
  it('refuses tools instead of silently dropping them', async () => {
    const gateway = new Gateway({ adapter: mockProvider(), model: 'mock' })
    const stream = gateway.stream({
      messages: [{ role: 'user', content: 'hi' }],
      tools: [{ name: 't' }],
    })
    await expect(stream.next()).rejects.toBeInstanceOf(ConfigurationError)
  })
})

// ---- audit --------------------------------------------------------------------------

describe('AuditRecord tool calls', () => {
  const record = (extra: Partial<ConstructorParameters<typeof AuditRecord>[0]> = {}): AuditRecord =>
    new AuditRecord({
      traceId: 't',
      provider: 'p',
      model: 'm',
      timestampUtc: '2026-01-01T00:00:00+00:00',
      ...extra,
    })

  it('omits toolCalls when empty so existing chains still verify', () => {
    const plain = record()
    expect('toolCalls' in plain.toJSON()).toBe(false)
    expect(plain.toCanonicalJson()).not.toContain('toolCalls')
    const withCalls = record({ toolCalls: [{ name: 't', argumentsHash: 'h' }] })
    expect(withCalls.toJSON()['toolCalls']).toEqual([{ name: 't', argumentsHash: 'h' }])
    expect(withCalls.contentHash()).not.toBe(plain.contentHash())

    const first = record()
    const second = record({
      previousHash: first.contentHash(),
      toolCalls: [{ name: 't', argumentsHash: 'h' }],
    })
    const third = record({ previousHash: second.contentHash() })
    expect(verifyChain([first, second, third])).toBe(true)
    second.toolCalls[0]!.argumentsHash = 'tampered'
    expect(verifyChain([first, second, third])).toBe(false)
  })

  it('a call without tool calls records none', async () => {
    const sink = new CapturingSink()
    const gateway = new Gateway({ adapter: mockProvider(), model: 'mock' }).use(
      auditInterceptor({ sink }),
    )
    const response = await gateway.complete({ messages: [{ role: 'user', content: 'hi' }] })
    expect(response.audit!.toolCalls).toEqual([])
    expect('toolCalls' in response.audit!.toJSON()).toBe(false)
  })

  it('the hash changes with the arguments but not with their key order', async () => {
    const sink = new CapturingSink()
    const audit = auditInterceptor({ sink })
    for (const args of [{ a: 1, b: 2 }, { b: 2, a: 1 }, { a: 1, b: 3 }]) {
      const response = new GavioResponse({
        traceId: 't',
        content: '',
        model: 'm',
        provider: 'p',
        toolCalls: [new ToolCall({ id: 'c', name: 't', arguments: args })],
      })
      await audit.after!(response, newCtx())
    }
    const hashes = sink.records.map((r) => r.toolCalls[0]!.argumentsHash)
    expect(hashes[0]).toBe(hashes[1])
    expect(hashes[0]).not.toBe(hashes[2])
  })
})

// ---- cache --------------------------------------------------------------------------

describe('semanticCache with tools', () => {
  it('never replays a tool-call response', async () => {
    const { adapter, state } = toolCallingProvider([
      new ToolCall({ id: 'c', name: 't', arguments: { a: 1 } }),
    ])
    const gateway = new Gateway({ adapter, model: 'mock' }).use(semanticCache())
    const messages = [{ role: 'user', content: 'do it' }]

    const first = await gateway.complete({ messages })
    const second = await gateway.complete({ messages })

    expect(state.calls).toBe(2)
    expect(first.cacheHit).toBe(false)
    expect(second.cacheHit).toBe(false)
    expect(second.toolCalls).toEqual([new ToolCall({ id: 'c', name: 't', arguments: { a: 1 } })])
  })

  it('the cache key separates different tool sets', async () => {
    const base = mockProvider({ response: 'x' })
    let calls = 0
    const adapter = {
      providerName: 'mock',
      async complete(r: GavioRequest): Promise<GavioResponse> {
        calls += 1
        return base.complete(r)
      },
      async healthCheck(): Promise<boolean> {
        return true
      },
    }
    const gateway = new Gateway({ adapter, model: 'mock' }).use(semanticCache())
    const messages = [{ role: 'user', content: 'hello' }]
    await gateway.complete({ messages, tools: [{ name: 'a' }] })
    await gateway.complete({ messages, tools: [{ name: 'a' }] }) // same tools -> cached
    await gateway.complete({ messages, tools: [{ name: 'b' }] }) // different tools -> miss
    await gateway.complete({ messages, tools: [{ name: 'a' }], toolChoice: 'required' }) // miss
    await gateway.complete({ messages }) // no tools -> miss
    expect(calls).toBe(4)
  })

  it('requests with tools skip the semantic level', async () => {
    const base = mockProvider({ response: 'x' })
    let calls = 0
    const adapter = {
      providerName: 'mock',
      async complete(r: GavioRequest): Promise<GavioResponse> {
        calls += 1
        return base.complete(r)
      },
      async healthCheck(): Promise<boolean> {
        return true
      },
    }
    const gateway = new Gateway({ adapter, model: 'mock' }).use(
      semanticCache({ embedder: hashingEmbedder() }),
    )
    const messages = [{ role: 'user', content: 'hello there' }]
    await gateway.complete({ messages, tools: [{ name: 'a' }] })
    // Identical text, so a semantic hit would be certain — but the tools differ.
    const second = await gateway.complete({ messages, tools: [{ name: 'b' }] })
    expect(second.cacheHit).toBe(false)
    expect(calls).toBe(2)
  })
})

// ---- Tool Runtime bridge ------------------------------------------------------------

describe('Tool Runtime bridge', () => {
  it('maps tool calls to the call objects Tool Runtime accepts', () => {
    const calls = [new ToolCall({ id: 'call_1', name: 'lookup_invoice', arguments: { invoice_id: 'INV-1' } })]
    const mapped = toolCallsToRuntimeCalls(calls, { source: 'openai' })
    expect(mapped).toEqual([
      {
        id: 'call_1',
        name: 'lookup_invoice',
        arguments: { invoice_id: 'INV-1' },
        source: 'openai',
      },
    ])
    ;(mapped[0]!['arguments'] as Record<string, unknown>)['invoice_id'] = 'changed'
    expect(calls[0]!.arguments['invoice_id']).toBe('INV-1') // the bridge copies
    const withResult = toolCallsToRuntimeCalls(calls, { results: { call_1: { total: 5 } } })
    expect(withResult[0]!['result']).toEqual({ total: 5 })

    const decision = analyzeToolRuntime({ calls: mapped })
    expect(decision.callCount).toBe(1)
    expect(decision.decisions[0]!['tool_name']).toBe('lookup_invoice')
  })

  it('validates provider tool calls against definitions and permissions', () => {
    const definitions = [
      { name: 'lookup_invoice', permissions: ['read.billing'] },
      { name: 'delete_account', permissions: ['write.accounts'], risk: 'destructive' },
    ]
    const calls = [
      new ToolCall({ id: 'call_1', name: 'lookup_invoice', arguments: { invoice_id: 'INV-1' } }),
      new ToolCall({ id: 'call_2', name: 'delete_account', arguments: { account_id: 'A-9' } }),
    ]
    const denied = analyzeProviderToolCalls(calls, undefined, { definitions })
    expect(denied.decisions.map((d) => d['action'])).toEqual(['block', 'require_approval'])
    expect(denied.decisions[0]!['missing_permissions']).toEqual(['read.billing'])

    const granted = analyzeProviderToolCalls(
      calls.slice(0, 1),
      { permissions: ['read.billing'] },
      { definitions },
    )
    expect(granted.decisions[0]!['action']).toBe('allow')
    expect(granted.violations).toEqual([])
  })

  it('keeps calls already in the tool context and does not modify it', () => {
    const context = { tool_calls: [{ id: 'old', name: 'earlier', arguments: {} }] }
    const decision = analyzeProviderToolCalls(
      [new ToolCall({ id: 'new', name: 'later' })],
      context,
    )
    expect(decision.decisions.map((d) => d['tool_id'])).toEqual(['old', 'new'])
    expect('calls' in context).toBe(false)
  })
})
