package io.gavio;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.interceptors.Interceptor;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.spi.AuditFactory;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Fluent builder for {@link Gateway}. */
public final class GavioBuilder {

    private Provider provider;
    private String model;
    private ProviderAdapter adapter;
    private final List<Interceptor> interceptors = new ArrayList<>();
    private boolean devMode;
    private boolean dryRun;
    private PricingProvider pricing = new PricingProvider();

    public GavioBuilder provider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public GavioBuilder provider(String provider) {
        this.provider = Provider.coerce(provider);
        return this;
    }

    public GavioBuilder model(String model) {
        this.model = model;
        return this;
    }

    public GavioBuilder adapter(ProviderAdapter adapter) {
        this.adapter = adapter;
        return this;
    }

    public GavioBuilder use(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        return this;
    }

    public GavioBuilder pricing(PricingProvider pricing) {
        this.pricing = pricing;
        return this;
    }

    public GavioBuilder devMode(boolean enabled) {
        this.devMode = enabled;
        return this;
    }

    public GavioBuilder dryRun(boolean enabled) {
        this.dryRun = enabled;
        return this;
    }

    public Gateway build() {
        ProviderAdapter resolved = resolveAdapter();
        String resolvedModel = model != null ? model : defaultModel(resolved);
        List<Interceptor> chain = new ArrayList<>(interceptors);

        // Dev mode auto-wires a stdout audit interceptor if one is on the
        // classpath and none was added (parity with the Python builder).
        if (devMode) {
            AuditFactory factory = loadAuditFactory();
            if (factory != null) {
                boolean hasAudit = chain.stream().anyMatch(factory::isAudit);
                if (!hasAudit) {
                    chain.add(0, factory.createDefault());
                }
            }
        }

        return new Gateway(resolved, resolvedModel, chain, dryRun);
    }

    private ProviderAdapter resolveAdapter() {
        if (adapter != null) {
            return adapter;
        }
        if (devMode) {
            return new MockProvider(null, "mock-1", pricing);
        }
        if (provider == null) {
            throw new ConfigurationException(
                    "No provider configured. Call .provider(...), .adapter(...), or .devMode(true).");
        }
        throw new ConfigurationException(
                "Provider '" + provider.value() + "' has no built-in adapter wiring; "
                        + "pass an explicit .adapter(...) (e.g. OpenAiAdapter / AnthropicAdapter).");
    }

    private static AuditFactory loadAuditFactory() {
        for (AuditFactory f : ServiceLoader.load(AuditFactory.class)) {
            return f;
        }
        return null;
    }

    private static String defaultModel(ProviderAdapter adapter) {
        return switch (adapter.providerName()) {
            case "openai" -> "gpt-4o";
            case "anthropic" -> "claude-sonnet-4-6";
            default -> "mock";
        };
    }
}
