package io.gavio.inspector;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Synchronous fan-out of {@link InspectorEvent}s to subscribers (F-DX-09).
 *
 * <p>Every subscriber call is wrapped in try/catch — a failing subscriber must
 * never break a request. With no subscribers, {@link #emit} is a cheap no-op.
 */
public final class InspectorBus {

    private final CopyOnWriteArrayList<Consumer<InspectorEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();

    /** Register a subscriber. Called synchronously on the emitting thread. */
    public void subscribe(Consumer<InspectorEvent> subscriber) {
        subscribers.add(subscriber);
    }

    /** Remove a previously registered subscriber (e.g. a dead SSE connection). */
    public void unsubscribe(Consumer<InspectorEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /** Events that failed delivery to at least one subscriber. */
    public long dropped() {
        return dropped.get();
    }

    /** Deliver an event to all subscribers; subscriber failures are counted, never thrown. */
    public void emit(InspectorEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        for (Consumer<InspectorEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Throwable t) {
                dropped.incrementAndGet();
            }
        }
    }
}
