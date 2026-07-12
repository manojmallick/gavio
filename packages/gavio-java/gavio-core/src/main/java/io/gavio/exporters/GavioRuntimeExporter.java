package io.gavio.exporters;

import io.gavio.inspector.InspectorEvent;

/** Runtime event exporter subscribed to the Gavio Inspector event stream. */
@FunctionalInterface
public interface GavioRuntimeExporter {

    /**
     * Export one InspectorEvent/Gavio runtime event.
     *
     * <p>Exporters are called synchronously on the request path. Implementations
     * should do small, bounded work and avoid depending on raw prompt or response
     * content being present.
     */
    void exportEvent(InspectorEvent event);

    default void flush() {}

    default void close() {}
}
