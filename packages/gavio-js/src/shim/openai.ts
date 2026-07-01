/**
 * OpenAI drop-in shim (F-DX-04) — point existing OpenAI SDK code at Gavio.
 *
 *   import { Gateway } from 'gavio'
 *   import { GavioOpenAI } from 'gavio/shim/openai'
 *
 *   const client = new GavioOpenAI(new Gateway({ provider: 'openai', model: 'gpt-4o' }))
 *   const resp = await client.chat.completions.create({
 *     model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }],
 *   })
 *   console.log(resp.choices[0].message.content)
 */

import type { Gateway } from '../gateway.js'
import type { GavioResponse } from '../response.js'
import type { Message } from '../types.js'

export interface ChatCompletion {
  id: string
  object: 'chat.completion'
  model: string
  choices: { index: number; message: { role: string; content: string }; finish_reason: string }[]
  usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number }
  gavio: { costUsd: number; cacheHit: boolean; interceptorsFired: string[] }
}

export interface CreateParams {
  messages: Message[]
  model?: string
  temperature?: number
  // eslint-disable-next-line @typescript-eslint/naming-convention
  max_tokens?: number
}

function toCompletion(resp: GavioResponse): ChatCompletion {
  return {
    id: resp.traceId,
    object: 'chat.completion',
    model: resp.modelVersion || resp.model,
    choices: [
      { index: 0, message: { role: 'assistant', content: resp.content }, finish_reason: 'stop' },
    ],
    usage: {
      prompt_tokens: resp.usage.promptTokens,
      completion_tokens: resp.usage.completionTokens,
      total_tokens: resp.usage.totalTokens,
    },
    gavio: {
      costUsd: resp.costUsd,
      cacheHit: resp.cacheHit,
      interceptorsFired: resp.interceptorsFired,
    },
  }
}

class Completions {
  constructor(private readonly gw: Gateway) {}

  async create(params: CreateParams): Promise<ChatCompletion> {
    const resp = await this.gw.complete({
      messages: params.messages,
      model: params.model,
      options: { temperature: params.temperature ?? 0.7, maxTokens: params.max_tokens ?? 1024 },
    })
    return toCompletion(resp)
  }
}

/** OpenAI-client-shaped facade over a Gavio Gateway. */
export class GavioOpenAI {
  readonly chat: { completions: Completions }

  constructor(gateway: Gateway) {
    this.chat = { completions: new Completions(gateway) }
  }
}
