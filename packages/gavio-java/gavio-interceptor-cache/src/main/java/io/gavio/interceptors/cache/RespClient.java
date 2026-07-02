package io.gavio.interceptors.cache;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal blocking RESP2 (Redis Serialization Protocol) client over {@link Socket}.
 *
 * <p>Hand-rolled, zero runtime dependencies — matches the project convention of
 * stdlib-only providers/interceptors (cf. {@code java.net.http.HttpClient} for
 * the HTTP-based provider adapters). Supports exactly the commands the cache
 * backends need (GET, SET, DEL, SADD, SREM, SMEMBERS); not a general client.
 */
final class RespClient {

    private final String host;
    private final int port;
    private Socket socket;
    private BufferedInputStream in;
    private OutputStream out;

    RespClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    private synchronized void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        socket = new Socket(host, port);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    synchronized Object command(Object... args) {
        try {
            ensureConnected();
            out.write(encode(args));
            out.flush();
            return readReply();
        } catch (IOException e) {
            closeQuietly();
            throw new RuntimeException("redis command failed: " + e.getMessage(), e);
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        socket = null;
    }

    private static byte[] encode(Object[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (Object arg : args) {
            String s = String.valueOf(arg);
            int len = s.getBytes(StandardCharsets.UTF_8).length;
            sb.append('$').append(len).append("\r\n").append(s).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new IOException("connection closed while reading reply");
        }
        String line = readLine();
        return switch (type) {
            case '+' -> line;
            case '-' -> throw new RuntimeException("redis error: " + line);
            case ':' -> Long.parseLong(line);
            case '$' -> readBulkString(line);
            case '*' -> readArray(line);
            default -> throw new IOException("unexpected RESP type byte: " + (char) type);
        };
    }

    private Object readBulkString(String lengthLine) throws IOException {
        int len = Integer.parseInt(lengthLine);
        if (len < 0) {
            return null;
        }
        byte[] data = readN(len);
        readN(2); // trailing CRLF
        return new String(data, StandardCharsets.UTF_8);
    }

    private Object readArray(String countLine) throws IOException {
        int count = Integer.parseInt(countLine);
        if (count < 0) {
            return null;
        }
        List<Object> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(readReply());
        }
        return items;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // consume trailing \n
                break;
            }
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private byte[] readN(int n) throws IOException {
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(data, off, n - off);
            if (read == -1) {
                throw new IOException("connection closed while reading " + n + " bytes");
            }
            off += read;
        }
        return data;
    }
}
