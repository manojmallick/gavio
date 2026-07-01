/**
 * Token cost tracking (F-GOV-01).
 *
 * Prices are USD per 1,000 tokens, sourced from public provider pricing and
 * overridable. Unknown models price at zero (warned once) rather than guessing.
 * Prices are intentionally data, not code — update the table, not the estimator.
 */

import type { TokenUsage } from './types.js'

/** model -> [inputPer1kUsd, outputPer1kUsd] */
const DEFAULT_PRICES: Record<string, [number, number]> = {
  // OpenAI
  'gpt-4o': [0.0025, 0.01],
  'gpt-4o-mini': [0.00015, 0.0006],
  o1: [0.015, 0.06],
  'o1-mini': [0.0011, 0.0044],
  // Anthropic
  'claude-sonnet-4-6': [0.003, 0.015],
  'claude-sonnet-4-20250514': [0.003, 0.015],
  'claude-haiku-4-5': [0.0008, 0.004],
  'claude-opus-4-1': [0.015, 0.075],
  // Gemini (approximate public pricing; override via config)
  'gemini-2.0-flash': [0.0001, 0.0004],
  'gemini-1.5-flash': [0.000075, 0.0003],
  'gemini-1.5-pro': [0.00125, 0.005],
  // Local (Ollama) / mock are free.
  mock: [0.0, 0.0],
}

/** Estimates request cost from token usage and a model price table. */
export class PricingProvider {
  private prices: Record<string, [number, number]>
  private warned = new Set<string>()

  constructor(prices?: Record<string, [number, number]>) {
    this.prices = { ...DEFAULT_PRICES, ...(prices ?? {}) }
  }

  setPrice(model: string, inputPer1k: number, outputPer1k: number): void {
    this.prices[model] = [inputPer1k, outputPer1k]
  }

  rates(model: string): [number, number] {
    const rate = this.prices[model]
    if (rate !== undefined) return rate
    // try a prefix match (e.g. "gpt-4o-2024-..." -> "gpt-4o")
    for (const [known, value] of Object.entries(this.prices)) {
      if (model.startsWith(known)) return value
    }
    if (!this.warned.has(model)) {
      // eslint-disable-next-line no-console
      console.warn(`[gavio:pricing] no pricing for model '${model}'; treating as free`)
      this.warned.add(model)
    }
    return [0.0, 0.0]
  }

  estimate(model: string, usage: TokenUsage): number {
    const [inRate, outRate] = this.rates(model)
    let cost = (usage.promptTokens / 1000.0) * inRate
    cost += (usage.completionTokens / 1000.0) * outRate
    return roundTo(cost, 8)
  }
}

function roundTo(value: number, decimals: number): number {
  const factor = 10 ** decimals
  return Math.round(value * factor) / factor
}

/** Rough token estimate (~4 chars/token) for providers without a tokenizer. */
export function estimateTokens(text: string): number {
  if (!text) return 0
  return Math.max(1, Math.floor(text.length / 4))
}
