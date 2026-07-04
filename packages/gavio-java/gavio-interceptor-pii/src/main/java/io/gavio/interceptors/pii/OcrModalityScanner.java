package io.gavio.interceptors.pii;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Reference OCR {@link ModalityScanner} backed by the optional
 * {@code net.sourceforge.tess4j:tess4j} dependency, loaded reflectively so it is
 * not a compile-time dependency of Gavio.
 *
 * <p>Extracts text from an image for the text PII scanners; performs no face
 * detection. Throws a clear error if the optional dependency is not on the
 * classpath.
 */
public final class OcrModalityScanner implements ModalityScanner {

    private final String lang;

    public OcrModalityScanner() {
        this("eng");
    }

    public OcrModalityScanner(String lang) {
        this.lang = lang;
    }

    @Override
    public String name() {
        return "ocr";
    }

    @Override
    public ModalityScanResult scan(byte[] image) {
        try {
            Class<?> tessClass = Class.forName("net.sourceforge.tess4j.Tesseract");
            Object tesseract = tessClass.getDeclaredConstructor().newInstance();
            tessClass.getMethod("setLanguage", String.class).invoke(tesseract, lang);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            String text = (String) tessClass.getMethod("doOCR", BufferedImage.class).invoke(tesseract, img);
            return new ModalityScanResult(text == null ? "" : text, List.of());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "OcrModalityScanner requires the optional 'net.sourceforge.tess4j:tess4j' "
                            + "dependency — add it to enable image OCR",
                    e);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException("OCR failed", e);
        }
    }
}
