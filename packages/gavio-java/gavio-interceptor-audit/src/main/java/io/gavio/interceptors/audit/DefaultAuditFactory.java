package io.gavio.interceptors.audit;

import io.gavio.interceptors.Interceptor;
import io.gavio.spi.AuditFactory;

/**
 * SPI implementation that lets {@code gavio-core}'s dev mode auto-wire a stdout
 * audit interceptor when this artifact is on the classpath.
 */
public final class DefaultAuditFactory implements AuditFactory {

    @Override
    public Interceptor createDefault() {
        return new AuditInterceptor();
    }

    @Override
    public boolean isAudit(Interceptor interceptor) {
        return interceptor instanceof AuditInterceptor;
    }
}
