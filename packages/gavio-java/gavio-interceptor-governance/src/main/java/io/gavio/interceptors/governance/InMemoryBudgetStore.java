package io.gavio.interceptors.governance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Dependency-free budget store for tests, local apps, and examples. */
public final class InMemoryBudgetStore implements BudgetStore {
    private final Map<String, Double> spend = new ConcurrentHashMap<>();

    public InMemoryBudgetStore() {
    }

    public InMemoryBudgetStore(Map<String, Double> initial) {
        spend.putAll(initial);
    }

    @Override
    public double get(String scope) {
        return spend.getOrDefault(scope, 0.0);
    }

    @Override
    public double add(String scope, double costUsd) {
        return spend.merge(scope, costUsd, Double::sum);
    }
}
