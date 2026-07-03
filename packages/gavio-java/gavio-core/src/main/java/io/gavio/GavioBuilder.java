package io.gavio;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.inspector.CaptureMode;
import io.gavio.inspector.Inspector;
import io.gavio.inspector.InspectorConfig;
import io.gavio.inspector.PipelineInfo;
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
    private InspectorConfig inspectorConfig;

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

    /**
     * Configure the Gavio Inspector (F-DX-09). The inspector is OFF by default
     * — dev mode does not auto-enable it.
     */
    public GavioBuilder inspect(InspectorConfig config) {
        this.inspectorConfig = config;
        return this;
    }

    /** Enable/disable the inspector with default settings (F-DX-09). */
    public GavioBuilder inspect(boolean enabled) {
        this.inspectorConfig = InspectorConfig.builder().enabled(enabled).build();
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

        Inspector inspector = buildInspector(resolved, resolvedModel, chain);
        return new Gateway(resolved, resolvedModel, chain, dryRun, inspector);
    }

    /**
     * Create (and optionally start) the inspector (F-DX-09). Returns null when
     * disabled — the disabled path leaves gateway behaviour completely unchanged.
     */
    private Inspector buildInspector(
            ProviderAdapter resolved, String resolvedModel, List<Interceptor> chain) {
        InspectorConfig cfg = inspectorConfig;
        if (cfg == null) {
            cfg = configFromEnv();
        }
        if (cfg == null || !cfg.enabled()) {
            return null;
        }
        cfg.validate(devMode);
        List<String> names = new ArrayList<>();
        for (Interceptor i : chain) {
            names.add(i.name());
        }
        PipelineInfo info = new PipelineInfo(
                resolved.providerName(), resolvedModel, devMode, dryRun, names);
        Inspector inspector = new Inspector(cfg, cfg.effectiveMode(devMode), info);
        inspector.setPricing(pricing); // /api/simulate-cost reuses the builder's pricing
        if (cfg.startServer()) {
            inspector.start();
        }
        return inspector;
    }

    /** GAVIO_INSPECT=1 enables inspector defaults; PORT/MODE env refine them. */
    private static InspectorConfig configFromEnv() {
        String flag = System.getenv("GAVIO_INSPECT");
        if (!"1".equals(flag) && !"true".equalsIgnoreCase(String.valueOf(flag))) {
            return null;
        }
        InspectorConfig.Builder b = InspectorConfig.builder().enabled(true);
        String port = System.getenv("GAVIO_INSPECT_PORT");
        if (port != null && !port.isEmpty()) {
            try {
                b.port(Integer.parseInt(port.trim()));
            } catch (NumberFormatException e) {
                throw new ConfigurationException("GAVIO_INSPECT_PORT is not a number: " + port);
            }
        }
        String mode = System.getenv("GAVIO_INSPECT_MODE");
        if (mode != null && !mode.isEmpty()) {
            try {
                b.mode(CaptureMode.fromWire(mode));
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        "GAVIO_INSPECT_MODE must be full|redacted|metadata, got: " + mode);
            }
        }
        return b.build();
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
