package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.dto.AiDocumentExtractResponse;
import com.quizopia.backend.question.exception.AiAuthoringErrorCode;
import com.quizopia.backend.question.exception.AiAuthoringException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiDocumentExtractor}: TXT (UTF-8, binary rejection),
 * text PDF (PDFBox, created in-test), scanned-PDF behavior (no text),
 * DOCX/PPTX (POI, created in-test), magic-byte mismatch, size and
 * truncation bounds, and the no-text rejection path.
 */
class AiDocumentExtractorTest {

    private static final AiAuthoringProperties PROPS = new AiAuthoringProperties(
            true, "openai", "https://api.example.test/v1", "k", "m",
            30, 5_000_000, 1_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 1_000, 3_600);

    private AiDocumentExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new AiDocumentExtractor(PROPS);
    }

    // ------------------------------------------------------------------
    // TXT
    // ------------------------------------------------------------------

    @Test
    void extractsUtf8TxtIncludingVietnamese() {
        byte[] bytes = "Bài giảng mạng máy tính — Networking lecture\nLine 2".getBytes(StandardCharsets.UTF_8);
        AiDocumentExtractResponse out = extractor.extract(file("lecture.txt", "text/plain", bytes));

        assertThat(out.name()).isEqualTo("lecture.txt");
        assertThat(out.truncated()).isFalse();
        assertThat(out.charCount()).isEqualTo(out.text().length());
        assertThat(out.text()).contains("Bài giảng");
        assertThat(out.text()).contains("Networking lecture");
    }

    @Test
    void rejectsBinaryTxtWithNulBytes() {
        byte[] bytes = {'a', 0, 'b'};
        assertThatThrownBy(() -> extractor.extract(file("blob.txt", "text/plain", bytes)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_INVALID))
                .hasMessageContaining("not UTF-8 text");
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    @Test
    void extractsTextPdf() throws Exception {
        byte[] pdf = pdfWithText("The TCP three-way handshake establishes a connection.");
        AiDocumentExtractResponse out = extractor.extract(file("lecture.pdf", "application/pdf", pdf));

        assertThat(out.text()).contains("three-way handshake");
        assertThat(out.truncated()).isFalse();
    }

    @Test
    void rejectsScannedPdfWithoutText() throws Exception {
        byte[] pdf = pdfWithText(null);
        assertThatThrownBy(() -> extractor.extract(file("scan.pdf", "application/pdf", pdf)))
                .isInstanceOfSatisfying(AiAuthoringException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_NO_TEXT))
                .hasMessageContaining("scanned");
    }

    @Test
    void rejectsPdfMagicMismatch() {
        assertThatThrownBy(() -> extractor.extract(file("fake.pdf", "application/pdf",
                "just plain text".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_INVALID))
                .hasMessageContaining("does not match its extension");
    }

    // ------------------------------------------------------------------
    // DOCX / PPTX
    // ------------------------------------------------------------------

    @Test
    void extractsDocx() throws Exception {
        byte[] docx = docxWithParagraphs("First paragraph about OSI layers.", "Second paragraph.");
        AiDocumentExtractResponse out = extractor.extract(file("notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx));

        assertThat(out.text()).contains("OSI layers");
        assertThat(out.text()).contains("Second paragraph.");
    }

    @Test
    void extractsPptxSlides() throws Exception {
        byte[] pptx = pptxWithSlides("Slide one: TCP header", "Slide two: UDP");
        AiDocumentExtractResponse out = extractor.extract(file("deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx));

        assertThat(out.text()).contains("TCP header");
        assertThat(out.text()).contains("UDP");
    }

    @Test
    void rejectsCorruptDocx() {
        assertThatThrownBy(() -> extractor.extract(file("broken.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[] {'P', 'K', 3, 4, ' ', 'n', 'o', 't', ' ', 'a', ' ', 'z', 'i', 'p'})))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_INVALID));
    }

    // ------------------------------------------------------------------
    // Metadata bounds
    // ------------------------------------------------------------------

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> extractor.extract(file("virus.exe", "application/octet-stream", new byte[] {1})))
                .isInstanceOfSatisfying(AiAuthoringException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_UNSUPPORTED_TYPE));
    }

    @Test
    void rejectsOversizeFile() {
        byte[] bytes = new byte[8];
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", bytes) {
            @Override
            public long getSize() {
                return 5_000_001;
            }
        };
        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_TOO_LARGE));
    }

    @Test
    void truncatesToExtractedCharCap() {
        byte[] bytes = "x".repeat(1_500).getBytes(StandardCharsets.UTF_8);
        AiDocumentExtractResponse out = extractor.extract(file("long.txt", "text/plain", bytes));

        assertThat(out.truncated()).isTrue();
        assertThat(out.charCount()).isEqualTo(1_000);
        assertThat(out.text()).hasSize(1_000);
    }

    @Test
    void truncatesLongTextPdfViaBoundedWriter() throws Exception {
        // Raw text far beyond the 1k cap: the bounded writer must stop
        // materializing output long before PDFBox finishes the page.
        byte[] pdf = pdfWithText("quizopia ".repeat(400));
        AiDocumentExtractResponse out = extractor.extract(file("long.pdf", "application/pdf", pdf));

        assertThat(out.truncated()).isTrue();
        assertThat(out.charCount()).isEqualTo(1_000);
        assertThat(out.text()).hasSize(1_000);
    }

    @Test
    void rejectsWhitespaceOnlyDocument() {
        assertThatThrownBy(() -> extractor.extract(file("empty.txt", "text/plain", "   \n\t ".getBytes())))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_DOCUMENT_NO_TEXT));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    /** Minimal single-page PDF; {@code text == null} produces a text-free page. */
    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithParagraphs(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                doc.createParagraph().createRun().setText(paragraph);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pptxWithSlides(String... slideTexts) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String slideText : slideTexts) {
                XSLFSlide slide = ppt.createSlide();
                slide.createTextBox().setText(slideText);
            }
            ppt.write(out);
            return out.toByteArray();
        }
    }
}
