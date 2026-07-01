package io.gavio.interceptors.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-dependency hashed bag-of-words embedder (L2-normalised).
 *
 * <p>Parity note: Python uses BLAKE2b-64; the JDK lacks BLAKE2b, so this uses
 * the first 8 bytes of SHA-256. Deterministic; the cache is per-process so
 * cross-language byte-parity is not required.
 */
public final class HashingEmbedder implements Embedder {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private final int dim;

    public HashingEmbedder() {
        this(256);
    }

    public HashingEmbedder(int dim) {
        this.dim = dim;
    }

    @Override
    public double[] embed(String text) {
        double[] vec = new double[dim];
        Matcher m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            byte[] d = sha256(m.group());
            long n = 0L;
            for (int i = 0; i < 8; i++) {
                n = (n << 8) | (d[i] & 0xFFL);
            }
            int bucket = (int) Math.floorMod(n, (long) dim);
            vec[bucket] += 1.0;
        }
        double norm = 0.0;
        for (double x : vec) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return vec;
        }
        for (int i = 0; i < dim; i++) {
            vec[i] /= norm;
        }
        return vec;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
