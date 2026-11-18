package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.dto.AiDocumentExtractResponse;
import com.quizopia.backend.question.exception.AiAuthoringErrorCode;
import com.quizopia.backend.question.exception.AiAuthoringException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Server-side text extraction for AI-assisted authoring documents
 * (Phase 5, issue #113): TXT (UTF-8), text-based PDF (PDFBox), DOCX and
 * PPTX (Apache POI, already on the classpath for the Excel import).
 *
 * <p>Bounded: the upload size is checked BEFORE any byte is parsed; the
 * extracted text is capped at {@link AiAuthoringProperties#maxExtractedChars()}
 * characters with an explicit {@code truncated} flag. No OCR — a scanned /
 * image-only PDF (or any document with no extractable text) is rejected with
 * {@link AiAuthoringErrorCode#AI_DOCUMENT_NO_TEXT}. Nothing is persisted.
 */
@Component
public class AiDocumentExtractor {

    private static final List<String> TXT_EXTENSIONS = List.of(".txt");
    private static final List<String> PDF_EXTENSIONS = List.of(".pdf");
    private static final List<String> DOCX_EXTENSIONS = List.of(".docx");
    private static final List<String> PPTX_EXTENSIONS = List.of(".pptx");

    private final AiAuthoringProperties properties;

    public AiDocumentExtractor(AiAuthoringProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates metadata, then extracts plain text. Call AFTER authorization —
     * the caller (service) enforces bank access before any byte is read.
     */
    public AiDocumentExtractResponse extract(MultipartFile file) {
        String name = validatedName(file);
        if (file.getSize() > properties.maxFileBytes()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_TOO_LARGE,
                    "File exceeds the " + (properties.maxFileBytes() / (1024 * 1024)) + " MiB limit");
        }

        byte[] bytes;
        try {
            bytes = file.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "Failed to read the uploaded file");
        }

        String lower = name.toLowerCase(Locale.ROOT);
        String text;
        if (endsWithAny(lower, TXT_EXTENSIONS)) {
            text = extractTxt(bytes);
        } else if (endsWithAny(lower, PDF_EXTENSIONS)) {
            requireMagic(bytes, "%PDF", name);
            text = extractPdf(bytes);
        } else if (endsWithAny(lower, DOCX_EXTENSIONS)) {
            text = extractOfficeZip(bytes, name, true);
        } else {
            text = extractOfficeZip(bytes, name, false);
        }

        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_NO_TEXT,
                    "No extractable text found — scanned/image-only documents are not supported");
        }

        boolean truncated = normalized.length() > properties.maxExtractedChars();
        String bounded = truncated ? normalized.substring(0, properties.maxExtractedChars()) : normalized;
        return new AiDocumentExtractResponse(name, bounded, truncated, bounded.length());
    }

    private String validatedName(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "File name must not be blank");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean supported = endsWithAny(lower, TXT_EXTENSIONS) || endsWithAny(lower, PDF_EXTENSIONS)
                || endsWithAny(lower, DOCX_EXTENSIONS) || endsWithAny(lower, PPTX_EXTENSIONS);
        if (!supported) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_UNSUPPORTED_TYPE,
                    "Only .txt, .pdf, .docx and .pptx documents are accepted");
        }
        return filename;
    }

    private static boolean endsWithAny(String lowerName, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (lowerName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** TXT: UTF-8 with malformed-sequence replacement; NUL bytes mean binary. */
    private String extractTxt(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                        "The .txt upload is not UTF-8 text");
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * PDF via PDFBox. Text is written through a char-capped Writer so a
     * crafted/decompression-amplified document cannot materialize unbounded
     * text in memory; the cap is {@code 2x} the final limit (headroom for
     * layout newlines before truncation). PDFBox may also throw unchecked
     * exceptions on corrupt streams — both map to AI_DOCUMENT_INVALID.
     */
    private String extractPdf(byte[] bytes) {
        BoundedWriter writer = new BoundedWriter(extractionAllowance());
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            new PDFTextStripper().writeText(doc, writer);
        } catch (BoundedWriter.CapReachedException e) {
            // expected stop when the allowance is exhausted
        } catch (IOException | RuntimeException e) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "The .pdf upload could not be parsed");
        }
        return writer.toString();
    }

    /** Hard ceiling on raw extracted text BEFORE truncation (2x final cap). */
    private int extractionAllowance() {
        return properties.maxExtractedChars() * 2;
    }

    /** DOCX (XWPF) or PPTX (XSLF) — both are OOXML zip packages ("PK" magic). */
    private String extractOfficeZip(byte[] bytes, String name, boolean docx) {
        requireMagic(bytes, "PK", name);
        try {
            return docx ? extractDocx(bytes) : extractPptx(bytes);
        } catch (IOException | RuntimeException e) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "The " + (docx ? ".docx" : ".pptx") + " upload could not be parsed");
        }
    }

    /** DOCX: iterate body elements and STOP at the allowance (bounded memory). */
    private String extractDocx(byte[] bytes) throws IOException {
        StringBuilder out = new StringBuilder();
        int allowance = extractionAllowance();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (out.length() > allowance) {
                    break;
                }
                if (element instanceof XWPFParagraph paragraph) {
                    appendLine(out, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (var row : table.getRows()) {
                        StringBuilder rowText = new StringBuilder();
                        for (var cell : row.getTableCells()) {
                            if (!rowText.isEmpty()) {
                                rowText.append(" | ");
                            }
                            rowText.append(cell.getText());
                        }
                        appendLine(out, rowText.toString());
                    }
                }
            }
        }
        return out.toString();
    }

    private String extractPptx(byte[] bytes) throws IOException {
        StringBuilder out = new StringBuilder();
        int allowance = extractionAllowance();
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            for (XSLFSlide slide : slides.getSlides()) {
                if (out.length() > allowance) {
                    break;
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendLine(out, textShape.getText());
                    } else if (shape instanceof XSLFTable table) {
                        for (var row : table.getRows()) {
                            StringBuilder rowText = new StringBuilder();
                            for (XSLFTableCell cell : row.getCells()) {
                                if (!rowText.isEmpty()) {
                                    rowText.append(" | ");
                                }
                                rowText.append(cell.getText());
                            }
                            appendLine(out, rowText.toString());
                        }
                    }
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append(text.stripTrailing()).append('\n');
    }

    /**
     * Char-capped {@link java.io.Writer}: stops accepting text once the
     * allowance is exceeded, bounding PDFTextStripper's output memory.
     */
    private static final class BoundedWriter extends java.io.Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int allowance;

        BoundedWriter(int allowance) {
            this.allowance = allowance;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (buffer.length() >= allowance) {
                throw new CapReachedException();
            }
            buffer.append(cbuf, off, Math.min(len, allowance - buffer.length() + 1));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return buffer.toString();
        }

        /** Signals the hard char ceiling; carried through writeText's IOException. */
        static final class CapReachedException extends IOException {
        }
    }

    /** Rejects content whose magic bytes contradict its extension. */
    private static void requireMagic(byte[] bytes, String magic, String name) {
        byte[] prefix = magic.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < prefix.length) {
            throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                    "File content does not match its extension: " + name);
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                throw new AiAuthoringException(AiAuthoringErrorCode.AI_DOCUMENT_INVALID,
                        "File content does not match its extension: " + name);
            }
        }
    }
}
