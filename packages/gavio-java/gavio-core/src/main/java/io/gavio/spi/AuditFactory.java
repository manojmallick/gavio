package io.gavio.spi;

import io.gavio.interceptors.Interceptor;

/**
 * SPI hook so {@code gavio-core} can auto-wire a stdout audit interceptor in
 * dev mode without depending on {@code gavio-interceptor-audit}.
 *
 * <p>The audit module registers an implementation via {@code META-INF/services}.
 * If no implementation is on the classpath, dev mode simply skips auto-auditing.
 */
public interface AuditFactory {

    /** Create a default (stdout) audit interceptor. */
    Interceptor createDefault();

    /** True if {@code interceptor} is an audit interceptor (so we don't double-add). */
    boolean isAudit(Interceptor interceptor);
}
