/**
 * Normalised tool-calling helpers shared by provider adapters and interceptors (F-ADP-03).
 *
 * Everything here is pure: no network, no clock. Provider adapters use it to translate
 * between Gavio's normalised tool calls and each provider's native shape; `PiiGuard` uses
 * it to find the strings inside tool-call arguments. Port of `gavio.tool_calling` (Python).
 */

import { ConfigurationError, MalformedToolCallError } from './errors.js'
import { FinishReason, TokenUsage, ToolCall } from './types.js'
import type { Message, ToolChoice, ToolDefinition } from './types.js'

export const EMPTY_OBJECT_SCHEMA: Record<string, unknown> = { type: 'object', properties: {} }

type JsonObject = Record<string, unknown>

/**
 * Untyped provider wire JSON (a native request or response body). Adapters read these
 * defensively — every member may be absent — so they are not worth a type per provider.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Wire = Record<string, any>

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

// ---- messages -----------------------------------------------------------------------
// Messages are plain objects. The spec spells the tool keys camelCase (`toolCalls`,
// `toolCallId`, `isError`), which is what this SDK writes; Python's snake_case spelling
// is accepted on read so documents from either SDK can be passed straight in.

/** The key under which `message` carries tool calls, or null. */
export function toolCallsKey(message: Message): 'tool_calls' | 'toolCalls' | null {
  for (const key of ['tool_calls', 'toolCalls'] as const) {
    if (key in message) return key
  }
  return null
}

/** Tool calls carried by an assistant message (empty for every other message). */
export function messageToolCalls(message: Message): ToolCall[] {
  const key = toolCallsKey(message)
  const raw = key === null ? undefined : message[key]
  if (!Array.isArray(raw)) return []
  return raw.map((item) => ToolCall.from(item))
}

export function messageToolCallId(message: Message): string | null {
  const value = message['tool_call_id'] ?? message['toolCallId']
  return value ? String(value) : null
}

export function messageIsError(message: Message): boolean {
  return Boolean(message['is_error'] ?? message['isError'] ?? false)
}

/** A message's `content` as text; null (OpenAI-style tool-call turns) is empty. */
export function messageText(message: Message): string {
  return message.content ?? ''
}

/**
 * Apply `fn` to every scalar leaf of a JSON value, keeping its structure.
 *
 * Object keys are not visited: they come from the tool's declared schema, not from
 * user data.
 */
export function mapLeaves(value: unknown, fn: (leaf: unknown) => unknown): unknown {
  if (Array.isArray(value)) return value.map((item) => mapLeaves(item, fn))
  if (isObject(value)) {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, mapLeaves(item, fn)]))
  }
  return fn(value)
}

/** {@link mapLeaves} for an async `fn`; leaves are visited one at a time, in document order. */
export async function mapLeavesAsync(
  value: unknown,
  fn: (leaf: unknown) => Promise<unknown>,
): Promise<unknown> {
  if (Array.isArray(value)) {
    const out: unknown[] = []
    for (const item of value) out.push(await mapLeavesAsync(item, fn))
    return out
  }
  if (isObject(value)) {
    const out: JsonObject = {}
    for (const [key, item] of Object.entries(value)) out[key] = await mapLeavesAsync(item, fn)
    return out
  }
  return fn(value)
}

// ---- tool declarations and choice ---------------------------------------------------

/** The provider-relevant part of a `ToolDefinition`: name, description, input schema. */
export interface ToolSpec {
  name: string
  description: string | null
  inputSchema: JsonObject | null
}

/** Normalise `GavioRequest.tools`. Governance fields (permissions, risk) are dropped. */
export function toolSpecs(tools: ToolDefinition[] | null | undefined): ToolSpec[] {
  const specs: ToolSpec[] = []
  for (const tool of tools ?? []) {
    const name = tool?.name
    if (!name) throw new ConfigurationError('tool declaration is missing a name')
    const schema = tool.inputSchema ?? tool.input_schema
    specs.push({
      name: String(name),
      description: tool.description || null,
      inputSchema: isObject(schema) && Object.keys(schema).length > 0 ? { ...schema } : null,
    })
  }
  return specs
}

/** Normalised `toolChoice`: `auto`, `none`, `required` or `tool` + `name`. */
export interface ResolvedToolChoice {
  mode: 'auto' | 'none' | 'required' | 'tool'
  name: string | null
}

export function parseToolChoice(choice: ToolChoice | null | undefined): ResolvedToolChoice | null {
  if (choice === null || choice === undefined) return null
  if (typeof choice === 'string') {
    if (choice === 'auto' || choice === 'none' || choice === 'required') {
      return { mode: choice, name: null }
    }
    throw new ConfigurationError(
      `unsupported toolChoice ${JSON.stringify(choice)} (use auto, none, required or { name })`,
    )
  }
  if (isObject(choice) && choice['name']) {
    return { mode: 'tool', name: String(choice['name']) }
  }
  throw new ConfigurationError('toolChoice must be auto, none, required or { name: <tool> }')
}

/** The tool choice to send — null when the request declares no tools. */
export function activeToolChoice(
  tools: ToolDefinition[] | null | undefined,
  choice: ToolChoice | null | undefined,
): ResolvedToolChoice | null {
  return tools && tools.length > 0 ? parseToolChoice(choice) : null
}

// ---- ids and arguments --------------------------------------------------------------

/** Deterministic id for providers that send none: `call_<index>_<name>`. */
export function syntheticCallId(index: number, name: string): string {
  return `call_${index}_${name}`
}

/** Compact JSON, insertion order — the string OpenAI-style APIs expect. */
export function dumpArguments(args: JsonObject): string {
  return JSON.stringify(args)
}

/**
 * Parse native tool-call arguments into an object.
 *
 * Accepts an already-parsed object, a JSON string, an empty string or null/undefined
 * (no arguments). Anything else throws {@link MalformedToolCallError} naming the tool
 * only — the arguments may hold PII.
 */
export function parseArguments(raw: unknown, toolName: string): JsonObject {
  if (raw === null || raw === undefined || (typeof raw === 'string' && raw.trim() === '')) {
    return {}
  }
  let value = raw
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw)
    } catch {
      throw new MalformedToolCallError(
        `tool call '${toolName}' has arguments that are not valid JSON`,
      )
    }
  }
  if (!isObject(value)) {
    throw new MalformedToolCallError(
      `tool call '${toolName}' has arguments that are not a JSON object`,
    )
  }
  return value
}

/** Map a native stop reason; a plain stop that produced tool calls is `tool_calls`. */
export function normaliseFinishReason(
  native: string | null | undefined,
  mapping: Readonly<Record<string, FinishReason>>,
  toolCalls: readonly ToolCall[],
): FinishReason | null {
  const reason: FinishReason | null =
    native === null || native === undefined
      ? null
      : Object.hasOwn(mapping, native)
        ? mapping[native]!
        : FinishReason.OTHER
  if (toolCalls.length > 0 && (reason === null || reason === FinishReason.STOP)) {
    return FinishReason.TOOL_CALLS
  }
  return reason
}

// ---- parsed provider response -------------------------------------------------------

export interface ProviderCompletionInit {
  content?: string
  toolCalls?: ToolCall[]
  finishReason?: FinishReason | null
  usage?: TokenUsage
  modelVersion?: string | null
}

/** A provider response parsed into normalised parts (`fromProviderResponse`). */
export class ProviderCompletion {
  readonly content: string
  readonly toolCalls: ToolCall[]
  readonly finishReason: FinishReason | null
  readonly usage: TokenUsage
  readonly modelVersion: string | null

  constructor(init: ProviderCompletionInit = {}) {
    this.content = init.content ?? ''
    this.toolCalls = init.toolCalls ?? []
    this.finishReason = init.finishReason ?? null
    this.usage = init.usage ?? new TokenUsage()
    this.modelVersion = init.modelVersion ?? null
  }

  /** The spec (camelCase) shape used by the shared test vectors. */
  toWire(): {
    content: string
    toolCalls: ReturnType<ToolCall['toJSON']>[]
    finishReason: FinishReason | null
    usage: ReturnType<TokenUsage['toJSON']>
    modelVersion: string | null
  } {
    return {
      content: this.content,
      toolCalls: this.toolCalls.map((call) => call.toJSON()),
      finishReason: this.finishReason,
      usage: this.usage.toJSON(),
      modelVersion: this.modelVersion,
    }
  }
}
