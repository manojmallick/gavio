/**
 * InspectorBus — synchronous fan-out of inspector events to subscribers
 * (ring buffer, SSE streams, tests). Observation must never break a request:
 * every subscriber call is isolated in try/catch, and a throwing subscriber
 * only increments the dropped-event counter.
 */

import type { InspectorEvent } from './events.js'

export type InspectorSubscriber = (event: InspectorEvent) => void

export class InspectorBus {
  private readonly subscribers: Set<InspectorSubscriber> = new Set()
  private droppedEvents = 0

  /** Register a subscriber; returns an unsubscribe function. */
  subscribe(fn: InspectorSubscriber): () => void {
    this.subscribers.add(fn)
    return () => {
      this.subscribers.delete(fn)
    }
  }

  /** Deliver one event to every subscriber. No subscribers → no-op. */
  emit(event: InspectorEvent): void {
    if (this.subscribers.size === 0) return
    for (const fn of this.subscribers) {
      try {
        fn(event)
      } catch {
        // A broken subscriber must never break the request path.
        this.droppedEvents += 1
      }
    }
  }

  /** Number of subscriber deliveries that failed and were dropped. */
  get drops(): number {
    return this.droppedEvents
  }

  get subscriberCount(): number {
    return this.subscribers.size
  }
}
