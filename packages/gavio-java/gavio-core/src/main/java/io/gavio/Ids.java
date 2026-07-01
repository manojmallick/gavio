package io.gavio;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID v7 generation — time-sortable, monotonic identifiers for traces.
 *
 * <p>UUID v7 layout (RFC 9562): 48-bit Unix millisecond timestamp, 4-bit
 * version, 12 bits sequence/randomness (rand_a), 2-bit variant, 62 bits of
 * randomness (rand_b). This is a direct port of the Python reference generator.
 */
public final class Ids {

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastMs = -1L;
    private static int seq = 0; // 12-bit per-millisecond sequence in rand_a

    private Ids() {
    }

    /**
     * Return a (unix_ms, sequence) pair that is monotonically non-decreasing.
     * Within one millisecond the 12-bit sequence increments (RFC 9562 method 1);
     * on overflow the timestamp is nudged forward.
     */
    private static long[] nextTimestampAndSeq() {
        synchronized (LOCK) {
            long nowMs = System.currentTimeMillis();
            if (nowMs > lastMs) {
                lastMs = nowMs;
                seq = RANDOM.nextInt() & 0x0FFF;
            } else {
                seq += 1;
                if (seq > 0x0FFF) {
                    lastMs += 1;
                    seq = 0;
                }
            }
            return new long[] {lastMs, seq};
        }
    }

    /** Return a new UUID version 7 (time-ordered, monotonic within a process). */
    public static UUID uuid7() {
        long[] pair = nextTimestampAndSeq();
        long unixMs = pair[0];
        long randA = pair[1] & 0x0FFFL;

        // 48 bits of millisecond timestamp + version + rand_a -> most-significant 64 bits.
        long msb = ((unixMs & 0xFFFFFFFFFFFFL) << 16)
                | (0x7L << 12)   // version 7
                | randA;

        // 2-bit variant (10) + 62 bits randomness -> least-significant 64 bits.
        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long lsb = (0b10L << 62) | randB;

        return new UUID(msb, lsb);
    }

    /** Return a fresh trace id as a string. */
    public static String newTraceId() {
        return uuid7().toString();
    }
}
