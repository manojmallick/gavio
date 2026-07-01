/** Tiny JSON-over-HTTP helper built on native fetch (keeps core dependency-free). */

import {
  ProviderUnavailableError,
  RateLimitError,
  ServerError,
} from '../errors.js'

/**
 * POST `payload` as JSON and return the parsed response.
 *
 * Maps HTTP status families onto Gavio's transient error types so the
 * retry/fallback policies can react.
 */
export async function postJson(
  url: string,
  payload: unknown,
  headers: Record<string, string>,
  timeoutSeconds = 30.0,
): Promise<Record<string, unknown>> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutSeconds * 1000)
  let resp: Response
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify(payload),
      signal: controller.signal,
    })
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    throw new ProviderUnavailableError(`network error: ${reason}`)
  } finally {
    clearTimeout(timer)
  }

  if (!resp.ok) {
    const body = (await resp.text().catch(() => '')).slice(0, 200)
    if (resp.status === 429) {
      throw new RateLimitError(`429 from provider: ${body}`)
    }
    if (resp.status >= 500) {
      throw new ServerError(`${resp.status} from provider: ${body}`)
    }
    throw new ProviderUnavailableError(`${resp.status} from provider: ${body}`)
  }

  return (await resp.json()) as Record<string, unknown>
}
