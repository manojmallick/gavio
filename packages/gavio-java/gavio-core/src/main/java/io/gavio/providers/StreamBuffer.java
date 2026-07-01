package io.gavio.providers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Accumulate a provider stream for post-interceptors (F-REL-06).
 *
 * <p>Post-interceptors (guardrails, PII restore, audit) need the <em>complete</em>
 * response, so a streamed reply is buffered in full before the post pipeline runs
 * and before any chunk reaches the caller. This trades first-token latency for the
 * guarantee that every interceptor sees — and can rewrite or block — the whole
 * response.
 *
 * <p>Lives in core (not the reliability module) because the core {@code Gateway}
 * uses it and core cannot depend on the reliability module.
 */
public final class StreamBuffer {

    private final StringBuilder sb = new StringBuilder();

    /** Add one streamed chunk. */
    public synchronized void append(String chunk) {
        sb.append(chunk);
    }

    /** The full buffered response so far. */
    public synchronized String text() {
        return sb.toString();
    }

    /** Total buffered length in characters. */
    public synchronized int length() {
        return sb.length();
    }

    /** Subscribe to a provider stream and accumulate every chunk. */
    public static CompletableFuture<StreamBuffer> collect(Flow.Publisher<String> publisher) {
        StreamBuffer buffer = new StreamBuffer();
        CompletableFuture<StreamBuffer> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                buffer.append(item);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(buffer);
            }
        });
        return done;
    }
}
