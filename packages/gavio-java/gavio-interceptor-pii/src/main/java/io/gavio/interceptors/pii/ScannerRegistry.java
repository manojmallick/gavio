package io.gavio.interceptors.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Registry of scanners, discoverable by entity type at runtime. */
public final class ScannerRegistry {

    private final List<PiiScanner> scanners = new ArrayList<>();

    public ScannerRegistry() {
    }

    public ScannerRegistry(List<PiiScanner> scanners) {
        if (scanners != null) {
            scanners.forEach(this::register);
        }
    }

    public ScannerRegistry register(PiiScanner scanner) {
        scanners.add(scanner);
        return this;
    }

    /** Return scanners sorted by tier (lowest first). */
    public List<PiiScanner> scanners() {
        List<PiiScanner> sorted = new ArrayList<>(scanners);
        sorted.sort(Comparator.comparingInt(PiiScanner::tier));
        return sorted;
    }

    public List<PiiScanner> byEntityType(String entityType) {
        List<PiiScanner> out = new ArrayList<>();
        for (PiiScanner s : scanners) {
            if (s.entityType().equals(entityType)) {
                out.add(s);
            }
        }
        return out;
    }

    public int size() {
        return scanners.size();
    }
}
