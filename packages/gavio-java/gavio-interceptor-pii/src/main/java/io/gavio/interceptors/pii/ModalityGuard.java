package io.gavio.interceptors.pii;

import io.gavio.GavioException.PiiBlockedException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.pii.scanners.DefaultScanners;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Scan {@code request.images()} for PII before the provider call (F-SEC-09).
 *
 * <p>Each {@link ModalityScanner} extracts text (OCR) and/or direct detections
 * (e.g. faces); extracted text is run through the standard tier-1 PII text
 * scanners. Detected entity types are recorded on the context, so they land in
 * the AuditRecord's {@code piiEntityTypes}. With {@code onDetect="block"}, any
 * detection fails the call with {@link PiiBlockedException}.
 */
public final class ModalityGuard implements Interceptor {

    private final List<ModalityScanner> scanners;
    private final List<PiiScanner> textScanners;
    private final String onDetect;

    public ModalityGuard(List<ModalityScanner> scanners) {
        this(scanners, null, "tag");
    }

    public ModalityGuard(List<ModalityScanner> scanners, List<PiiScanner> textScanners, String onDetect) {
        if (!onDetect.equals("tag") && !onDetect.equals("block")) {
            throw new IllegalArgumentException("onDetect must be 'tag' or 'block'");
        }
        this.scanners = List.copyOf(scanners);
        this.textScanners = textScanners != null ? List.copyOf(textScanners) : DefaultScanners.defaults();
        this.onDetect = onDetect;
    }

    @Override
    public String name() {
        return "modality_guard";
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        if (request.images().isEmpty()) {
            return CompletableFuture.completedFuture(request);
        }
        Set<String> found = new TreeSet<>();
        for (byte[] image : request.images()) {
            for (ModalityScanner scanner : scanners) {
                ModalityScanResult result = scanner.scan(image);
                found.addAll(result.entityTypes());
                if (!result.text().isEmpty()) {
                    ScanContext scanCtx = new ScanContext();
                    for (PiiScanner ts : textScanners) {
                        if (!ts.scan(result.text(), scanCtx).isEmpty()) {
                            found.add(ts.entityType());
                        }
                    }
                }
            }
        }
        if (!found.isEmpty()) {
            ctx.recordPii(new ArrayList<>(found));
            if (onDetect.equals("block")) {
                return CompletableFuture.failedFuture(new PiiBlockedException(new ArrayList<>(found)));
            }
        }
        return CompletableFuture.completedFuture(request);
    }
}
