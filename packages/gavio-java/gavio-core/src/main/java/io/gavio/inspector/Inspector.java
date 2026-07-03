package io.gavio.inspector;

import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.types.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Composite wiring bus + ring buffer + optional HTTP server (F-DX-09/F-DX-10).
 * One instance per gateway; created at build time when inspection is enabled.
 */
public final class Inspector {

    /** Keep in sync with the parent pom's project.version. */
    public static final String SDK_VERSION = "0.9.0";

    /**
     * Re-fires a captured request through the live gateway pipeline (F-DX-11).
     * The gateway wires this to its own {@code complete(...)} at build time, so
     * a replay always runs the full interceptor chain — never bypassed.
     */
    @FunctionalInterface
    public interface ReplayHandler {

        /**
         * Replay a request.
         *
         * @param messages the (possibly edited) messages to send
         * @param model the model to use
         * @param metadata request metadata (carries {@code replay_of})
         * @param options provider options overrides
         * @return the response future from the live pipeline
         */
        CompletableFuture<GavioResponse> replay(
                List<Message> messages, String model,
                Map<String, Object> metadata, Map<String, Object> options);
    }

    private final InspectorConfig config;
    private final CaptureMode mode;
    private final PipelineInfo pipeline;
    private final InspectorBus bus = new InspectorBus();
    private final RingBuffer buffer;
    private InspectorServer server;
    /** Wired by the Gateway: POST /api/replay re-fires through the live pipeline. */
    private volatile ReplayHandler replayHandler;
    /** Used by /api/simulate-cost; the builder passes its PricingProvider. */
    private volatile PricingProvider pricing = new PricingProvider();

    public Inspector(InspectorConfig config, CaptureMode mode, PipelineInfo pipeline) {
        this.config = config;
        this.mode = mode;
        this.pipeline = pipeline;
        this.buffer = new RingBuffer(config.maxTraces());
        bus.subscribe(buffer);
    }

    public InspectorConfig config() {
        return config;
    }

    public CaptureMode mode() {
        return mode;
    }

    public PipelineInfo pipeline() {
        return pipeline;
    }

    public InspectorBus bus() {
        return bus;
    }

    public RingBuffer buffer() {
        return buffer;
    }

    /** The HTTP server, or null when {@code startServer(false)} / not started. */
    public InspectorServer server() {
        return server;
    }

    /** The replay handler wired by the gateway, or null when none is attached. */
    public ReplayHandler replayHandler() {
        return replayHandler;
    }

    /** Attach the live-pipeline replay handler (called by the gateway at build time). */
    public void setReplayHandler(ReplayHandler replayHandler) {
        this.replayHandler = replayHandler;
    }

    /** Pricing used by /api/simulate-cost. Never null. */
    public PricingProvider pricing() {
        return pricing;
    }

    /** Set the pricing provider (called by the builder); null resets to defaults. */
    public void setPricing(PricingProvider pricing) {
        this.pricing = pricing != null ? pricing : new PricingProvider();
    }

    /** Actual bound port, or -1 when the server is not running. */
    public int port() {
        return server != null ? server.getPort() : -1;
    }

    /** New per-request emitter feeding this inspector's bus. */
    public TraceEmitter newEmitter() {
        return new TraceEmitter(bus, mode);
    }

    /** Start the HTTP server (idempotent). */
    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = new InspectorServer(this);
            server.start();
        } catch (IOException e) {
            server = null;
            throw new UncheckedIOException("failed to start inspector server", e);
        }
    }

    /** Stop the HTTP server if running. */
    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
