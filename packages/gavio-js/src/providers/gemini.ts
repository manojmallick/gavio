/**
 * geminiAdapter — Google Generative Language API (generateContent).
 *
 * `toProviderPayload` / `fromProviderResponse` are pure functions (no network) so the
 * shared tool-calling test vectors can exercise them directly.
 *
 * Gemini has no tool-call ids: the adapter synthesises `call_<index>_<name>` on the way in
 * and matches tool results back to calls by name on the way out. If a response does carry a
 * `functionCall.id` it is kept, and echoed back on the matching `functionResponse`.
 */

import { ConfigurationError, MalformedToolCallError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import {
  ProviderCompletion,
  activeToolChoice,
  messageIsError,
  messageToolCallId,
  messageToolCalls,
  normaliseFinishReason,
  parseArguments,
  syntheticCallId,
  toolSpecs,
} from '../tool-calling.js'
import type { ResolvedToolChoice, ToolSpec, Wire } from '../tool-calling.js'
import { FinishReason, TokenUsage, ToolCall, type Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta'

const FINISH_REASONS: Record<string, FinishReason> = {
  STOP: FinishReason.STOP,
  MAX_TOKENS: FinishReason.LENGTH,
  SAFETY: FinishReason.CONTENT_FILTER,
  RECITATION: FinishReason.CONTENT_FILTER,
  BLOCKLIST: FinishReason.CONTENT_FILTER,
  PROHIBITED_CONTENT: FinishReason.CONTENT_FILTER,
  SPII: FinishReason.CONTENT_FILTER,
}

const CALLING_MODES: Record<ResolvedToolChoice['mode'], string> = {
  auto: 'AUTO',
  none: 'NONE',
  required: 'ANY',
  tool: 'ANY',
}

export interface GeminiAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

interface GeminiContent {
  role: string
  parts: Wire[]
}

/** Build the `generateContent` request body for `request`. */
export function toProviderPayload(request: GavioRequest): Wire {
  const { system, contents } = geminiToContents(request.messages)
  const payload: Wire = {
    contents,
    generationConfig: {
      temperature: request.temperature,
      maxOutputTokens: request.maxTokens,
    },
  }
  if (system) payload['systemInstruction'] = { parts: [{ text: system }] }
  const specs = toolSpecs(request.tools)
  if (specs.length > 0) {
    payload['tools'] = [{ functionDeclarations: specs.map(toNativeTool) }]
    const choice = activeToolChoice(request.tools, request.toolChoice)
    if (choice !== null) {
      payload['toolConfig'] = { functionCallingConfig: toNativeChoice(choice) }
    }
  }
  return payload
}

/** Parse a `generateContent` response body into normalised parts. */
export function fromProviderResponse(payload: Wire): ProviderCompletion {
  const candidate: Wire = payload['candidates']?.[0] ?? {}
  const parts: Wire[] = candidate['content']?.['parts'] ?? []
  const content = parts.map((p) => p['text'] ?? '').join('')
  const toolCalls: ToolCall[] = []
  for (const part of parts) {
    const call = part['functionCall']
    if (call !== undefined && call !== null) {
      toolCalls.push(fromNativeCall(toolCalls.length, call))
    }
  }
  const usage: Wire = payload['usageMetadata'] ?? {}
  return new ProviderCompletion({
    content,
    toolCalls,
    finishReason: normaliseFinishReason(candidate['finishReason'], FINISH_REASONS, toolCalls),
    usage: new TokenUsage(usage['promptTokenCount'] ?? 0, usage['candidatesTokenCount'] ?? 0),
  })
}

/** Map Gavio messages to Gemini contents + a system instruction. */
export function geminiToContents(messages: Message[]): {
  system: string | null
  contents: GeminiContent[]
} {
  let system: string | null = null
  const contents: GeminiContent[] = []
  // call id -> tool name, and whether the provider (not this adapter) supplied the id
  const callsById = new Map<string, { name: string; supplied: boolean }>()
  for (const message of messages) {
    const role = message.role
    const text = message.content || ''
    if (role === 'system') {
      system = system ? `${system}\n${text}` : text
      continue
    }
    const calls = messageToolCalls(message)
    const callId = messageToolCallId(message)
    if (role === 'assistant' && calls.length > 0) {
      const parts: Wire[] = text ? [{ text }] : []
      calls.forEach((call, index) => {
        const supplied = call.id !== syntheticCallId(index, call.name)
        callsById.set(call.id, { name: call.name, supplied })
        const native: Wire = { name: call.name, args: call.arguments }
        if (supplied) native['id'] = call.id
        parts.push({ functionCall: native })
      })
      contents.push({ role: 'model', parts })
    } else if (role === 'tool' && callId) {
      const known = callsById.get(callId)
      const name = known?.name ?? (message['name'] as string | undefined)
      if (!name) {
        throw new ConfigurationError(
          `tool result '${callId}' matches no earlier tool call and has no name`,
        )
      }
      const response = { [messageIsError(message) ? 'error' : 'output']: text }
      const result: Wire = { name, response }
      if (known?.supplied) result['id'] = callId
      const part = { functionResponse: result }
      const last = contents[contents.length - 1]
      if (isToolResultTurn(last)) last!.parts.push(part)
      else contents.push({ role: 'user', parts: [part] })
    } else {
      contents.push({ role: role === 'assistant' ? 'model' : 'user', parts: [{ text }] })
    }
  }
  return { system, contents }
}

function isToolResultTurn(content: GeminiContent | undefined): boolean {
  return (
    content !== undefined &&
    content.role === 'user' &&
    content.parts.every((part) => 'functionResponse' in part)
  )
}

function toNativeTool(spec: ToolSpec): Wire {
  const declaration: Wire = { name: spec.name }
  if (spec.description) declaration['description'] = spec.description
  // Gemini rejects an object schema without properties, so a no-argument tool omits it.
  const properties = spec.inputSchema?.['properties']
  if (
    typeof properties === 'object' &&
    properties !== null &&
    Object.keys(properties).length > 0
  ) {
    declaration['parameters'] = spec.inputSchema
  }
  return declaration
}

function toNativeChoice(choice: ResolvedToolChoice): Wire {
  const config: Wire = { mode: CALLING_MODES[choice.mode] }
  if (choice.mode === 'tool') config['allowedFunctionNames'] = [choice.name]
  return config
}

function fromNativeCall(index: number, call: Wire): ToolCall {
  const name: string | undefined = call['name']
  if (!name) throw new MalformedToolCallError(`functionCall at index ${index} has no name`)
  return new ToolCall({
    id: call['id'] || syntheticCallId(index, name),
    name,
    arguments: parseArguments(call['args'], name),
  })
}

class GeminiAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: GeminiAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey =
      options.apiKey ?? process.env['GEMINI_API_KEY'] ?? process.env['GOOGLE_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
  }

  get providerName(): string {
    return 'gemini'
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    if (!this.apiKey) throw new ConfigurationError('GEMINI_API_KEY not set')
    const started = performance.now()
    const url = `${this.baseUrl}/models/${request.model}:generateContent?key=${this.apiKey}`
    const data = await postJson(url, toProviderPayload(request), {}, this.timeoutSeconds)
    return this.buildCompletionResponse(request, fromProviderResponse(data), started)
  }

  async healthCheck(): Promise<boolean> {
    return !!this.apiKey
  }
}

/** Factory: build a Gemini provider adapter. */
export function geminiAdapter(options: GeminiAdapterOptions = {}): GeminiAdapter {
  return new GeminiAdapter(options)
}
