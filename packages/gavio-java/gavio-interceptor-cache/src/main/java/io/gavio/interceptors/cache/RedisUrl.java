package io.gavio.interceptors.cache;

import java.net.URI;

/** Parses {@code redis://host:port} connection strings (host/port only — no auth/db). */
record RedisUrl(String host, int port) {

    static RedisUrl parse(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort() != -1 ? uri.getPort() : 6379;
        return new RedisUrl(host, port);
    }
}
