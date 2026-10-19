/**
 * PiiGuard — the pre/post interceptor that detects and redacts PII.
 *
 * Pipeline rule (privacy): PII is scanned on every request before it reaches
 * the provider. Detected entities are redacted/masked/tagged or blocked. In
 * REDACT mode the original values are restored in the response.
 */

import type { InterceptorContext } from '../../context.js'
import { PiiBlockedError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import {
  mapLeaves,
  mapLeavesAsync,
  messageToolCalls,
  toolCallsKey,
} from '../../tool-calling.js'
import { PiiMode, Sensitivity, ToolCall } from '../../types.js'
import type { Message } from '../../types.js'
import type { Interceptor } from '../base.js'
import { ScanContext } from './context.js'
import { matchLength } from './match.js'
import type { PiiMatch } from './match.js'
import type { PolicyPack } from './policy-pack.js'
import { policyPackScanners } from './policy-pack.js'
import { scannerTier } from './scanner.js'
import type { PiiScanner } from './scanner.js'
import { defaultScanners } from './scanners/index.js'

const STATE_KEY = 'pii_replacements'

// Confidence floor per sensitivity level — matches below the floor are ignored.
const CONFIDENCE_FLOOR: Record<Sensitivity, number> = {
  [Sensitivity.STRICT]: 0.0,
  [Sensitivity.BALANCED]: 0.6,
  [Sensitivity.PERMISSIVE]: 0.9,
}

export interface PiiGuardOptions {
  scanners?: PiiScanner[]
  sensitivity?: Sensitivity
  mode?: PiiMode
  restoreOnResponse?: boolean
  logEntityTypes?: boolean
  dryRun?: boolean
  locale?: string
  language?: string
}

/**
 * Besides message `content` (tool-role messages included), the string and integer values
 * inside the `arguments` of assistant tool calls are scanned (F-ADP-03), so PII cannot
 * reach the provider through a replayed tool call. In REDACT mode the placeholders are
 * restored in the response's tool-call arguments, like in its content.
 */
class PiiGuard implements Interceptor {
  readonly name = 'pii_guard'
  readonly dryRunSafe = true

  private readonly scanners: PiiScanner[]
  private readonly sensitivity: Sensitivity
  private readonly mode: PiiMode
  private readonly restoreOnResponse: boolean
  private readonly logEntityTypes: boolean
  private readonly ownDryRun: boolean
  private readonly locale: string
  private readonly language: string

  constructor(options: PiiGuardOptions = {}) {
    this.scanners = options.scanners ?? defaultScanners()
    this.sensitivity = options.sensitivity ?? Sensitivity.STRICT
    this.mode = options.mode ?? PiiMode.REDACT
    this.restoreOnResponse = options.restoreOnResponse ?? true
    this.logEntityTypes = options.logEntityTypes ?? true
    this.ownDryRun = options.dryRun ?? false
    this.locale = options.locale ?? 'NL'
    this.language = options.language ?? 'en'
  }

  async before(request: GavioRequest, ctx: InterceptorContext): Promise<GavioRequest> {
    const scanCtx = new ScanContext(this.language, this.locale)
    const floor = CONFIDENCE_FLOOR[this.sensitivity]

    const newMessages: Message[] = []
    const allTypes: string[] = []
    const replacements: Record<string, string> =
      (ctx.state[STATE_KEY] as Record<string, string> | undefined) ?? {}

    const isDryRun = this.ownDryRun || ctx.dryRun

    const redact = async (text: string): Promise<string> => {
      const matches = await this.scanText(text, scanCtx, floor)
      for (const m of matches) allTypes.push(m.entityType)

      if (matches.length > 0 && this.mode === PiiMode.BLOCK) {
        const types = matches.map((m) => m.entityType)
        throw new PiiBlockedError(types)
      }

      if (matches.length > 0 && !isDryRun) {
        return this.apply(text, matches, replacements)
      }
      return text
    }

    const redactLeaf = async (leaf: unknown): Promise<unknown> => {
      if (typeof leaf === 'string') return redact(leaf)
      if (typeof leaf === 'number' && Number.isInteger(leaf)) {
        // A numeric identifier (card, BSN, phone) is PII too; a redacted number
        // becomes its placeholder string.
        const text = String(leaf)
        const redacted = await redact(text)
        return redacted === text ? leaf : redacted
      }
      return leaf
    }

    for (const message of request.messages) {
      const next: Message = { ...message }
      // OpenAI-style assistant turns may carry `content: null` beside tool calls.
      if (message.content !== null) next.content = await redact(message.content ?? '')
      const callsKey = toolCallsKey(message)
      if (callsKey !== null) {
        const calls: unknown[] = []
        for (const call of messageToolCalls(message)) {
          calls.push({
            ...call.toJSON(),
            arguments: await mapLeavesAsync(call.arguments, redactLeaf),
          })
        }
        next[callsKey] = calls
      }
      newMessages.push(next)
    }

    if (allTypes.length > 0) {
      ctx.recordPii(allTypes)
      if (this.logEntityTypes) {
        const unique = Array.from(new Set(allTypes)).sort()
        // eslint-disable-next-line no-console
        console.info(`[gavio:pii] detected entity types: ${unique.join(', ')}`)
      }
    }

    if (this.restoreOnResponse && Object.keys(replacements).length > 0) {
      ctx.state[STATE_KEY] = replacements
    }

    if (isDryRun) return request
    return request.copyWithMessages(newMessages)
  }

  async after(
    response: GavioResponse,
    ctx: InterceptorContext,
  ): Promise<GavioResponse> {
    if (!this.restoreOnResponse || this.mode !== PiiMode.REDACT) return response
    const replacements = ctx.state[STATE_KEY] as Record<string, string> | undefined
    if (!replacements || Object.keys(replacements).length === 0) return response
    let changed = false
    const restore = (text: string): string => {
      // Replace placeholder tokens back with original values.
      let out = text
      for (const [token, original] of Object.entries(replacements)) {
        out = out.split(token).join(original)
      }
      if (out !== text) changed = true
      return out
    }

    const content = restore(response.content)
    const toolCalls = response.toolCalls.map(
      (call) =>
        new ToolCall({
          id: call.id,
          name: call.name,
          arguments: mapLeaves(call.arguments, (leaf) =>
            typeof leaf === 'string' ? restore(leaf) : leaf,
          ) as Record<string, unknown>,
        }),
    )
    if (!changed) return response
    return response.copyWith({ content, toolCalls })
  }

  private async scanText(
    text: string,
    scanCtx: ScanContext,
    floor: number,
  ): Promise<PiiMatch[]> {
    const raw: PiiMatch[] = []
    const ordered = [...this.scanners].sort((a, b) => scannerTier(a) - scannerTier(b))
    for (const scanner of ordered) {
      const found = await scanner.scan(text, scanCtx)
      for (const match of found) {
        if (match.confidence >= floor) raw.push(match)
      }
    }
    return resolveOverlaps(raw)
  }

  private apply(
    text: string,
    matches: PiiMatch[],
    replacements: Record<string, string>,
  ): string {
    // Replace right-to-left so earlier offsets stay valid.
    const ordered = [...matches].sort((a, b) => b.start - a.start)
    let out = text
    for (const match of ordered) {
      const token = this.tokenFor(match)
      if (this.mode === PiiMode.REDACT) {
        replacements[token] = match.value
      }
      out = out.slice(0, match.start) + token + out.slice(match.end)
    }
    return out
  }

  private tokenFor(match: PiiMatch): string {
    if (this.mode === PiiMode.MASK) {
      return '*'.repeat(Math.max(matchLength(match), 1))
    }
    if (this.mode === PiiMode.TAG) {
      return `<${match.entityType}>${match.value}</${match.entityType}>`
    }
    // REDACT (default)
    return match.replacement || `[${match.entityType}]`
  }
}

/**
 * Drop lower-priority matches that overlap a kept one.
 *
 * Sort by start, then by descending span length (prefer the longer match),
 * then by confidence. Greedily keep non-overlapping matches.
 */
export function resolveOverlaps(matches: PiiMatch[]): PiiMatch[] {
  const ordered = [...matches].sort((a, b) => {
    if (a.start !== b.start) return a.start - b.start
    const lenDiff = matchLength(b) - matchLength(a)
    if (lenDiff !== 0) return lenDiff
    return b.confidence - a.confidence
  })
  const kept: PiiMatch[] = []
  let occupiedEnd = -1
  for (const match of ordered) {
    if (match.start >= occupiedEnd) {
      kept.push(match)
      occupiedEnd = match.end
    }
  }
  return kept
}

/** Factory: build a PiiGuard interceptor. */
export function piiGuard(options: PiiGuardOptions = {}): Interceptor {
  return new PiiGuard(options)
}

/** Factory: build a PiiGuard interceptor from one or more Policy Packs. */
export function piiGuardFromPolicyPack(
  packs: PolicyPack | PolicyPack[],
  options: Omit<PiiGuardOptions, 'scanners'> = {},
): Interceptor {
  const packList = Array.isArray(packs) ? packs : [packs]
  return new PiiGuard({ ...options, scanners: policyPackScanners(...packList) })
}
