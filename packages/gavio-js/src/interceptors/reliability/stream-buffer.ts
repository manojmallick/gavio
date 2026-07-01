/**
 * StreamBuffer (F-REL-06) — accumulate a provider stream for post-interceptors.
 *
 * Post-interceptors (guardrails, PII restore, audit) need the *complete*
 * response, so a streamed reply is buffered in full before the post pipeline
 * runs and before any chunk reaches the caller. This trades first-token latency
 * for the guarantee that every interceptor sees — and can rewrite or block — the
 * whole response.
 */
export class StreamBuffer {
  private readonly parts: string[] = []

  /** Add one streamed chunk. */
  append(chunk: string): void {
    this.parts.push(chunk)
  }

  /** The full buffered response so far. */
  text(): string {
    return this.parts.join('')
  }

  /** Total buffered length in characters. */
  get length(): number {
    return this.parts.reduce((n, p) => n + p.length, 0)
  }
}
