package io.gavio.interceptors;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import java.util.concurrent.CompletableFuture;

/**
 * A function that takes the final request and returns a response — i.e. the
 * provider call, possibly wrapped by reliability policies.
 */
@FunctionalInterface
public interface Executor {
    CompletableFuture<GavioResponse> execute(GavioRequest request);
}
