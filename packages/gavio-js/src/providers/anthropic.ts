/**
 * anthropicAdapter — Messages API (Claude Sonnet, Haiku, Opus).
 *
 * `toProviderPayload` / `fromProviderResponse` are pure functions (no network) so the
 * shared tool-calling test vectors can exercise them directly.
 */

import { ConfigurationError, MalformedToolCallError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import {
  EMPTY_OBJECT_SCHEMA,
  ProviderCompletion,
  activeToolChoice,
  messageIsError,
  messageToolCallId,
  messageToolCalls,
  normaliseFinishReason,
  parseArguments,
  toolSpecs,
} from '../tool-calling.js'
import type { ResolvedToolChoice, ToolSpec, Wire } from '../tool-calling.js'
import { FinishReason, TokenUsage, ToolCall } from '../types.js'
import type { Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://api.anthropic.com/v1'
const API_VERSION = '2023-06-01'

const FINISH_REASONS: Record<string, FinishReason> = {
  end_turn: FinishReason.STOP,
  stop_sequence: FinishReason.STOP,
  max_tokens: FinishReason.LENGTH,
  tool_use: FinishReason.TOOL_CALLS,
  refusal: FinishReason.CONTENT_FILTER,
}

/**
 * Build the Messages API request body for `request`.
 *
 * System messages move to the `system` field. An assistant turn with tool calls becomes
 * `tool_use` blocks; consecutive tool-role messages become one user turn of
 * `tool_result` blocks, as the API requires for parallel calls.
 */
export function toProviderPayload(request: GavioRequest): Wire {
  const [system, chat] = splitSystem(request.messages)
  const payload: Wire = {
    model: request.model,
    messages: toNativeMessages(chat),
    max_tokens: request.maxTokens,
    temperature: request.temperature,
  }
  if (system) payload['system'] = system
  const specs = toolSpecs(request.tools)
  if (specs.length > 0) {
    payload['tools'] = specs.map(toNativeTool)
    const choice = activeToolChoice(request.tools, request.toolChoice)
    if (choice !== null) payload['tool_choice'] = toNativeChoice(choice)
  }
  return payload
}

/** Parse a Messages API response body into normalised parts. */
export function fromProviderResponse(payload: Wire): ProviderCompletion {
  const blocks: Wire[] = payload['content'] ?? []
  const content = blocks
    .filter((b) => b['type'] === 'text')
    .map((b) => b['text'] ?? '')
    .join('')
  const toolCalls = blocks.filter((b) => b['type'] === 'tool_use').map(fromNativeBlock)
  const usage: Wire = payload['usage'] ?? {}
  return new ProviderCompletion({
    content,
    toolCalls,
    finishReason: normaliseFinishReason(payload['stop_reason'], FINISH_REASONS, toolCalls),
    usage: new TokenUsage(usage['input_tokens'] ?? 0, usage['output_tokens'] ?? 0),
    modelVersion: payload['model'] ?? null,
  })
}

function splitSystem(messages: Message[]): [string | null, Message[]] {
  const systemParts = messages.filter((m) => m.role === 'system').map((m) => m.content || '')
  const chat = messages.filter((m) => m.role !== 'system')
  const system = systemParts.length > 0 ? systemParts.join('\n') : null
  return [system, chat]
}

function toNativeMessages(messages: Message[]): Wire[] {
  const native: Wire[] = []
  for (const message of messages) {
    const calls = messageToolCalls(message)
    const callId = messageToolCallId(message)
    if (message.role === 'assistant' && calls.length > 0) {
      const text = message.content || ''
      const blocks: Wire[] = text ? [{ type: 'text', text }] : []
      for (const call of calls) {
        blocks.push({ type: 'tool_use', id: call.id, name: call.name, input: call.arguments })
      }
      native.push({ role: 'assistant', content: blocks })
    } else if (message.role === 'tool' && callId) {
      const block: Wire = {
        type: 'tool_result',
        tool_use_id: callId,
        content: message.content || '',
      }
      if (messageIsError(message)) block['is_error'] = true
      const last = native[native.length - 1]
      if (isToolResultTurn(last)) last!['content'].push(block)
      else native.push({ role: 'user', content: [block] })
    } else {
      native.push({ ...message })
    }
  }
  return native
}

function isToolResultTurn(message: Wire | undefined): boolean {
  if (message === undefined || message['role'] !== 'user') return false
  const content = message['content']
  return Array.isArray(content) && content.every((b: Wire) => b['type'] === 'tool_result')
}

function toNativeTool(spec: ToolSpec): Wire {
  const tool: Wire = { name: spec.name }
  if (spec.description) tool['description'] = spec.description
  tool['input_schema'] = spec.inputSchema ?? { ...EMPTY_OBJECT_SCHEMA }
  return tool
}

function toNativeChoice(choice: ResolvedToolChoice): Wire {
  if (choice.mode === 'tool') return { type: 'tool', name: choice.name }
  return { type: choice.mode === 'required' ? 'any' : choice.mode }
}

function fromNativeBlock(block: Wire): ToolCall {
  const name: string | undefined = block['name']
  if (!name || !block['id']) {
    throw new MalformedToolCallError('tool_use block is missing its id or name')
  }
  return new ToolCall({
    id: block['id'],
    name,
    arguments: parseArguments(block['input'], name),
  })
}

export interface AnthropicAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

/**
 * Talks to the Anthropic Messages endpoint. Anthropic splits the system prompt
 * from the message list, so any `role === "system"` messages are extracted into
 * the `system` field.
 */
class AnthropicAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: AnthropicAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['ANTHROPIC_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
  }

  get providerName(): string {
    return 'anthropic'
  }

  private headers(): Record<string, string> {
    if (!this.apiKey) {
      throw new ConfigurationError(
        'ANTHROPIC_API_KEY not set (pass apiKey or set the env var)',
      )
    }
    return {
      'x-api-key': this.apiKey,
      'anthropic-version': API_VERSION,
    }
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const data = await postJson(
      `${this.baseUrl}/messages`,
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

/** Factory: build an Anthropic provider adapter. */
export function anthropicAdapter(options: AnthropicAdapterOptions = {}): AnthropicAdapter {
  return new AnthropicAdapter(options)
}
