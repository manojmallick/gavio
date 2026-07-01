package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void uuid7VersionAndVariant() {
        UUID u = Ids.uuid7();
        assertEquals(7, u.version(), "expected UUID version 7");
        // RFC 4122 variant bits (10xx) live at the top of the least-significant 64 bits.
        long variant = (u.getLeastSignificantBits() >>> 62) & 0b11;
        assertEquals(0b10, variant, "expected RFC 4122 variant");
    }

    @Test
    void uuid7IsTimeOrdered() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(Ids.uuid7().toString());
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        assertEquals(sorted, ids, "monotonic uuid7 values should already be sorted");
    }

    @Test
    void newTraceIdUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(Ids.newTraceId());
        }
        assertEquals(100, ids.size());
    }

    @Test
    void uuid7ParsesBackToVersion7() {
        UUID u = Ids.uuid7();
        UUID round = UUID.fromString(u.toString());
        assertEquals(u, round);
        assertTrue(round.version() == 7);
    }
}
