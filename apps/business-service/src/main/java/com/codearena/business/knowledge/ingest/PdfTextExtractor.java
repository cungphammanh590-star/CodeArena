package com.codearena.business.knowledge.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfTextExtractor {

    public String extractFromPath(Path path) throws IOException {
        return extractFromBytes(Files.readAllBytes(path));
    }

    public String extractFromBytes(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.isEncrypted()) {
                throw new IOException("PDF is encrypted");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null ? "" : text.replace("\u0000", "").trim();
        }
    }

    public static boolean looksLikeText(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 40) {
            return false;
        }
        long meaningful = t.chars()
                .filter(c -> Character.isLetter(c)
                        || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        return meaningful >= 20;
    }
}
