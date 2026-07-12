package io.gavio.interceptors.governance;

/** Minimal spend store protocol for Cost Governance v2. */
public interface BudgetStore {
    double get(String scope);

    double add(String scope, double costUsd);
}
