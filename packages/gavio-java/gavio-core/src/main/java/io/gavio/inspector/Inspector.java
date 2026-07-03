package io.gavio.inspector;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Composite wiring bus + ring buffer + optional HTTP server (F-DX-09/F-DX-10).
 * One instance per gateway; created at build time when inspection is enabled.
 */
public final class Inspector {

    /** Keep in sync with the parent pom's project.version. */
    public static final String SDK_VERSION = "0.6.0";

    private final InspectorConfig config;
    private final CaptureMode mode;
    private final PipelineInfo pipeline;
    private final InspectorBus bus = new InspectorBus();
    private final RingBuffer buffer;
    private InspectorServer server;

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
            server = new InspectorServer(config, mode, buffer, bus, pipeline);
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
