package io.gavio.interceptors.cache;

/** Turns text into a fixed-length float vector (F-CACHE-02). */
@FunctionalInterface
public interface Embedder {
    double[] embed(String text);
}
