/** costRouter (F-GOV-06) — auto-route simple prompts to a cheaper model. */

import type { InterceptorContext } from '../../context.js'
import { estimateTokens } from '../../pricing.js'
import type { GavioRequest } from '../../request.js'
import type { Interceptor } from '../base.js'

const REASONING_KEYWORDS = new Set([
  'why', 'because', 'compare', 'trade-off', 'tradeoff', 'explain', 'analyze',
  'analyse', 'evaluate', 'design', 'architecture', 'review', 'debug',
  'reasoning', 'justify', 'critique',
])
const TOKEN = /[a-z0-9-]+/g

/** Scores prompt text in `[0, 1]` — higher means more complex. */
export interface ComplexityScorer {
  score(text: string): number
}

/** Zero-dependency default: prompt length + reasoning-keyword density. */
export function heuristicComplexityScorer(): ComplexityScorer {
  return {
    score(text: string): number {
      const tokens = estimateTokens(text)
      const lengthScore = Math.min(tokens / 200, 1.0) * 0.6
      const words = new Set(text.toLowerCase().match(TOKEN) ?? [])
      let keywordHits = 0
      for (const w of words) if (REASONING_KEYWORDS.has(w)) keywordHits += 1
      const keywordScore = Math.min(keywordHits / 3, 1.0) * 0.4
      return Math.min(lengthScore + keywordScore, 1.0)
    },
  }
}

export interface CostRouterOptions {
  simpleModel: string
  complexityThreshold?: number
  scorer?: ComplexityScorer
}

/**
 * Reroute a request to `simpleModel` when its complexity score is low.
 *
 * Register early in the chain, before caching, so a rerouted request's cache
 * key reflects the model it actually ran on. Register after `modelPolicy` if
 * RBAC should gate on the caller's *requested* model, not the rerouted one.
 */
export function costRouter(options: CostRouterOptions): Interceptor {
  const { simpleModel, complexityThreshold = 0.35, scorer = heuristicComplexityScorer() } = options
  return {
    name: 'cost_router',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      const score = scorer.score(request.promptText())
      const rerouted = score < complexityThreshold && request.model !== simpleModel
      ctx.state['cost_router'] = {
        rerouted,
        originalModel: request.model,
        complexityScore: score,
      }
      if (!rerouted) return request
      const result = request.copyWithMessages(request.messages)
      result.model = simpleModel
      return result
    },
  }
}
