/**
 * openaiAdapter — Chat Completions API (GPT-4o, o1, ...).
 *
 * `toProviderPayload` / `fromProviderResponse` are pure functions (no network) so the
 * shared tool-calling test vectors can exercise them directly. Azure OpenAI and
 * OpenRouter speak the same Chat Completions shape and reuse them.
 */

import { ConfigurationError, MalformedToolCallError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import {
  ProviderCompletion,
  activeToolChoice,
  dumpArguments,
  messageToolCallId,
  messageToolCalls,
  normaliseFinishReason,
  parseArguments,
  syntheticCallId,
  toolSpecs,
  EMPTY_OBJECT_SCHEMA,
} from '../tool-calling.js'
import type { ResolvedToolChoice, ToolSpec, Wire } from '../tool-calling.js'
import { FinishReason, TokenUsage, ToolCall } from '../types.js'
import type { Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://api.openai.com/v1'

const FINISH_REASONS: Record<string, FinishReason> = {
  stop: FinishReason.STOP,
  length: FinishReason.LENGTH,
  tool_calls: FinishReason.TOOL_CALLS,
  content_filter: FinishReason.CONTENT_FILTER,
}

/**
 * Build the Chat Completions request body for `request`.
 *
 * `includeModel: false` omits `model` for Azure, which routes by deployment.
 */
export function toProviderPayload(
  request: GavioRequest,
  options: { includeModel?: boolean } = {},
): Wire {
  const payload: Wire = {}
  if (options.includeModel ?? true) payload['model'] = request.model
  payload['messages'] = request.messages.map(toNativeMessage)
  payload['temperature'] = request.temperature
  payload['max_tokens'] = request.maxTokens
  const specs = toolSpecs(request.tools)
  if (specs.length > 0) {
    payload['tools'] = specs.map(toNativeTool)
    const choice = activeToolChoice(request.tools, request.toolChoice)
    if (choice !== null) payload['tool_choice'] = toNativeChoice(choice)
  }
  return payload
}

/** Parse a Chat Completions response body into normalised parts. */
export function fromProviderResponse(payload: Wire): ProviderCompletion {
  const choice: Wire = payload['choices']?.[0] ?? {}
  const message: Wire = choice['message'] ?? {}
  const toolCalls = ((message['tool_calls'] as Wire[] | undefined) ?? []).map((raw, index) =>
    fromNativeCall(index, raw),
  )
  const usage: Wire = payload['usage'] ?? {}
  return new ProviderCompletion({
    content: message['content'] || '',
    toolCalls,
    finishReason: normaliseFinishReason(choice['finish_reason'], FINISH_REASONS, toolCalls),
    usage: new TokenUsage(usage['prompt_tokens'] ?? 0, usage['completion_tokens'] ?? 0),
    modelVersion: payload['model'] ?? null,
  })
}

function toNativeMessage(message: Message): Wire {
  const calls = messageToolCalls(message)
  if (message.role === 'assistant' && calls.length > 0) {
    return {
      role: 'assistant',
      content: message.content || null,
      tool_calls: calls.map((call) => ({
        id: call.id,
        type: 'function',
        function: { name: call.name, arguments: dumpArguments(call.arguments) },
      })),
    }
  }
  const callId = messageToolCallId(message)
  if (message.role === 'tool' && callId) {
    return { role: 'tool', tool_call_id: callId, content: message.content || '' }
  }
  return { ...message }
}

function toNativeTool(spec: ToolSpec): Wire {
  const fn: Wire = { name: spec.name }
  if (spec.description) fn['description'] = spec.description
  fn['parameters'] = spec.inputSchema ?? { ...EMPTY_OBJECT_SCHEMA }
  return { type: 'function', function: fn }
}

function toNativeChoice(choice: ResolvedToolChoice): string | Wire {
  if (choice.mode === 'tool') return { type: 'function', function: { name: choice.name } }
  return choice.mode
}

function fromNativeCall(index: number, raw: Wire): ToolCall {
  const fn: Wire = raw['function'] ?? {}
  const name: string | undefined = fn['name']
  if (!name) throw new MalformedToolCallError(`tool call at index ${index} has no function name`)
  return new ToolCall({
    id: raw['id'] || syntheticCallId(index, name),
    name,
    arguments: parseArguments(fn['arguments'], name),
  })
}

export interface OpenAIAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  organization?: string
  pricing?: PricingProvider
}

class OpenAIAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number
  private readonly organization: string | undefined

  constructor(options: OpenAIAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['OPENAI_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
    this.organization = options.organization
  }

  get providerName(): string {
    return 'openai'
  }

  private headers(): Record<string, string> {
    if (!this.apiKey) {
      throw new ConfigurationError(
        'OPENAI_API_KEY not set (pass apiKey or set the env var)',
      )
    }
    const headers: Record<string, string> = { Authorization: `Bearer ${this.apiKey}` }
    if (this.organization) headers['OpenAI-Organization'] = this.organization
    return headers
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const data = await postJson(
      `${this.baseUrl}/chat/completions`,
      toProviderPayload(request),
      this.headers(),
      this.timeoutSeconds,
    )
    return this.buildCompletionResponse(request, fromProviderResponse(data), started)
  }

  async healthCheck(): Promise<boolean> {
    try {
      this.headers()
      return true
    } catch {
      return false
    }
  }
}

/** Factory: build an OpenAI provider adapter. */
export function openaiAdapter(options: OpenAIAdapterOptions = {}): OpenAIAdapter {
  return new OpenAIAdapter(options)
}
