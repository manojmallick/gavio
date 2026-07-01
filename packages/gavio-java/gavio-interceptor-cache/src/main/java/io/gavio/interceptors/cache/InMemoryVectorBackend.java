package io.gavio.interceptors.cache;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded, brute-force in-memory vector store. */
public final class InMemoryVectorBackend implements VectorBackend {

    private record Entry(double[] vector, Object value, Long expiresAtMs) {}

    private final int maxSize;
    private final Deque<Entry> items = new ArrayDeque<>();

    public InMemoryVectorBackend() {
        this(1000);
    }

    public InMemoryVectorBackend(int maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public synchronized void add(double[] vector, Object value, Long ttlSeconds) {
        Long expires = ttlSeconds == null ? null : System.currentTimeMillis() + ttlSeconds * 1000;
        items.addLast(new Entry(vector, value, expires));
        while (items.size() > maxSize) {
            items.removeFirst();
        }
    }

    @Override
    public synchronized Object query(double[] vector, double threshold) {
        long now = System.currentTimeMillis();
        Object best = null;
        double bestSim = threshold;
        for (Entry e : items) {
            if (e.expiresAtMs() != null && now > e.expiresAtMs()) {
                continue;
            }
            double sim = Vectors.cosine(vector, e.vector());
            if (sim >= bestSim) {
                bestSim = sim;
                best = e.value();
            }
        }
        return best;
    }

    @Override
    public synchronized void clear() {
        items.clear();
    }
}
