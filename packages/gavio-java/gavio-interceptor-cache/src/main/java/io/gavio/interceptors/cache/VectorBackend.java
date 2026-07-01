package io.gavio.interceptors.cache;

/** Nearest-neighbour store for the semantic cache (F-CACHE-02). */
public interface VectorBackend {
    void add(double[] vector, Object value, Long ttlSeconds);

    /** Return the value of the nearest entry with similarity >= threshold, else null. */
    Object query(double[] vector, double threshold);

    void clear();
}
