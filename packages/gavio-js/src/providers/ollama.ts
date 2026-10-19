/**
 * ollamaAdapter — local models via the Ollama chat API.
 *
 * `toProviderPayload` / `fromProviderResponse` are pure functions (no network).
 *
 * Tool calling follows the documented `/api/chat` shape (docs/api.md in ollama/ollama): tools
 * are `{ type: "function", function: {...} }` declarations, tool calls carry an object
 * `arguments` and no id, and a tool result is a `role: "tool"` message naming the tool in
 * `tool_name`. Ollama has no `tool_choice` parameter, so anything but `auto` is rejected.
 * The shape is taken from that documentation and the shared vectors; it has not been
 * exercised against a live Ollama server.
 */

import { ConfigurationError, MalformedToolCallError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import {
  EMPTY_OBJECT_SCHEMA,
  ProviderCompletion,
  activeToolChoice,
  messageToolCallId,
  messageToolCalls,
  normaliseFinishReason,
  parseArguments,
  syntheticCallId,
  toolSpecs,
} from '../tool-calling.js'
import type { ToolSpec, Wire } from '../tool-calling.js'
import { FinishReason, TokenUsage, ToolCall } from '../types.js'
import type { Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'http://localhost:11434'

const FINISH_REASONS: Record<string, FinishReason> = {
  stop: FinishReason.STOP,
  length: FinishReason.LENGTH,
}

/** Build the `/api/chat` request body for `request`. */
export function toProviderPayload(request: GavioRequest): Wire {
  const payload: Wire = {
    model: request.model,
    messages: toNativeMessages(request.messages),
    stream: false,
    options: { temperature: request.temperature },
  }
  const specs = toolSpecs(request.tools)
  if (specs.length > 0) {
    const choice = activeToolChoice(request.tools, request.toolChoice)
    if (choice !== null && choice.mode !== 'auto') {
      throw new ConfigurationError(
        "ollama's /api/chat has no toolChoice parameter; only 'auto' is supported",
      )
    }
    payload['tools'] = specs.map(toNativeTool)
  }
  return payload
}

/** Parse an `/api/chat` response body into normalised parts. */
export function fromProviderResponse(payload: Wire): ProviderCompletion {
  const message: Wire = payload['message'] ?? {}
  const toolCalls = ((message['tool_calls'] as Wire[] | undefined) ?? []).map((raw, index) =>
    fromNativeCall(index, raw),
  )
  return new ProviderCompletion({
    content: message['content'] || '',
    toolCalls,
    finishReason: normaliseFinishReason(payload['done_reason'], FINISH_REASONS, toolCalls),
    usage: new TokenUsage(payload['prompt_eval_count'] ?? 0, payload['eval_count'] ?? 0),
    modelVersion: payload['model'] ?? null,
  })
}

function toNativeMessages(messages: Message[]): Wire[] {
  const native: Wire[] = []
  const namesById = new Map<string, string>()
  for (const message of messages) {
    const calls = messageToolCalls(message)
    const callId = messageToolCallId(message)
    if (message.role === 'assistant' && calls.length > 0) {
      for (const call of calls) namesById.set(call.id, call.name)
      native.push({
        role: 'assistant',
        content: message.content || '',
        tool_calls: calls.map((call) => ({
          function: { name: call.name, arguments: call.arguments },
        })),
      })
    } else if (message.role === 'tool' && callId) {
      const result: Wire = { role: 'tool', content: message.content || '' }
      const name = namesById.get(callId) || (message['name'] as string | undefined)
      if (name) result['tool_name'] = name
      native.push(result)
    } else {
      native.push({ ...message })
    }
  }
  return native
}

function toNativeTool(spec: ToolSpec): Wire {
  const fn: Wire = { name: spec.name }
  if (spec.description) fn['description'] = spec.description
  fn['parameters'] = spec.inputSchema ?? { ...EMPTY_OBJECT_SCHEMA }
  return { type: 'function', function: fn }
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

export interface OllamaAdapterOptions {
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

class OllamaAdapter extends BaseProviderAdapter {
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: OllamaAdapterOptions = {}) {
    super(options.pricing)
    this.baseUrl = (options.baseUrl ?? process.env['OLLAMA_HOST'] ?? DEFAULT_BASE_URL).replace(
      /\/+$/,
      '',
    )
    this.timeoutSeconds = (options.timeoutMs ?? 60_000) / 1000
  }

  get providerName(): string {
    return 'ollama'
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const data = await postJson(
      `${this.baseUrl}/api/chat`,
      toProviderPayload(request),
      {},
      this.timeoutSeconds,
    )
    return this.buildCompletionResponse(request, fromProviderResponse(data), started)
  }

  async healthCheck(): Promise<boolean> {
    return true
  }
}

/** Factory: build an Ollama provider adapter. */
export function ollamaAdapter(options: OllamaAdapterOptions = {}): OllamaAdapter {
  return new OllamaAdapter(options)
}
