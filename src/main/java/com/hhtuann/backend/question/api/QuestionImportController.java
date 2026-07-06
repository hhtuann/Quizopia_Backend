package com.hhtuann.backend.question.api;

import com.hhtuann.backend.question.application.QuestionImportService;
import com.hhtuann.backend.question.dto.ImportResponse;
import com.hhtuann.backend.question.dto.ImportResult;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import com.hhtuann.backend.question.exception.QuestionImportHttpException;
import com.hhtuann.backend.question.importer.ExcelQuestionParser;
import com.hhtuann.backend.question.template.QuestionTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;

/**
 * REST endpoints for question import.
 *
 * <p>
 * Batch B2.1: {@code GET /api/questions/import-template} downloads the
 * in-memory generated xlsx template (requires {@code QUESTION_CREATE}).
 *
 * <p>
 * Batch B2.2: {@code POST /api/question-banks/{bankId}/questions/import}
 * uploads a filled-in workbook, parses it <em>outside</em> any persistence
 * transaction, then persists the valid rows in a single all-or-nothing
 * transaction (re-authorizing inside). Partial success returns HTTP 200 with
 * the row errors in the body; only hard pre-conditions (auth, file metadata,
 * unrecoverable structure) return 4xx.
 */
// NOTE: no class-level @RequestMapping. The two endpoints live under DIFFERENT
// base paths (/api/questions and /api/question-banks), so each method carries
// its own full-path mapping. Keeping them in ONE controller lets the scoped
// QuestionImportHttpExceptionHandler cover both.
@RestController
public class QuestionImportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String CONTENT_DISPOSITION = "attachment; filename=\"quizopia-question-import-template.xlsx\"";

    /** Hard cap on a single uploaded workbook (5 MiB). */
    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final String XLSX_EXTENSION = ".xlsx";

    private final QuestionTemplateService templateService;
    private final QuestionImportService importService;
    private final ExcelQuestionParser parser;

    public QuestionImportController(QuestionTemplateService templateService,
            QuestionImportService importService,
            ExcelQuestionParser parser) {
        this.templateService = templateService;
        this.importService = importService;
        this.parser = parser;
    }

    /**
     * Downloads the question-import Excel template.
     */
    @GetMapping("/api/questions/import-template")
    public ResponseEntity<byte[]> getImportTemplate(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        templateService.checkPermission(userId);
        byte[] xlsx = templateService.generateTemplate();
        return ResponseEntity.ok()
                .header("Content-Type", XLSX_MEDIA_TYPE.toString())
                .header("Content-Disposition", CONTENT_DISPOSITION)
                .body(xlsx);
    }

    /**
     * Imports questions from an uploaded xlsx workbook into a question bank.
     *
     * <p>
     * Phases:
     * <ol>
     * <li><b>Preflight auth</b> — runs the deny-by-default authorization
     * before any byte of the workbook is parsed. This guarantees an
     * unauthorized caller (no role / permission / ownership / school
     * scope, or non-ACTIVE bank) receives 403/404 regardless of the
     * uploaded file's content (it is never read).</li>
     * <li><b>File metadata validation</b> — empty / too large / wrong
     * extension / wrong content-type.</li>
     * <li><b>Parse</b> — the workbook is read and validated structurally
     * (outside the persistence transaction). The caller-owned
     * InputStream is closed via try-with-resources; the parser does not
     * close it.</li>
     * <li><b>Persist</b> — the transactional service re-authorizes and
     * persists the valid rows in a single all-or-nothing transaction.</li>
     * </ol>
     */
    @PostMapping(value = "/api/question-banks/{bankId}/questions/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importQuestions(
            @PathVariable Long bankId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {

        Long userId = Long.valueOf(jwt.getSubject());

        // Phase 0: preflight authorization (before parsing the workbook).
        importService.authorizeImportAccess(userId, bankId);

        // Phase 1: file metadata validation.
        validateFileMetadata(file);

        // Phase 2: parse the workbook (outside the persistence transaction).
        ImportResult parseResult;
        try (InputStream in = file.getInputStream()) {
            parseResult = parser.parse(in);
        } catch (QuestionException qe) {
            // FILE_INVALID (corrupt / non-xlsx) or TEMPLATE_INVALID (wrong sheet/header).
            throw qe;
        } catch (QuestionImportHttpException httpEx) {
            throw httpEx;
        } catch (Exception e) {
            // Any other IOException / POI failure while reading the upload.
            throw new QuestionImportHttpException(
                    QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "Failed to read uploaded file");
        }

        // Phase 3: persist (transactional; re-authorizes internally).
        ImportResponse response = importService.importParsedQuestions(userId, bankId, parseResult);
        return ResponseEntity.ok(response);
    }

    /**
     * Validates the uploaded part's metadata. Throws
     * {@link QuestionImportHttpException} (carrying
     * {@link QuestionErrorCode#QUESTION_IMPORT_FILE_INVALID}) with the
     * appropriate HTTP status, or {@link QuestionException} for the empty-file
     * case (default 400).
     */
    @SuppressWarnings("deprecation")
    private void validateFileMetadata(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new QuestionException(QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID, "File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new QuestionImportHttpException(
                    QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "File too large");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()
                || !filename.toLowerCase(Locale.ROOT).endsWith(XLSX_EXTENSION)) {
            throw new QuestionImportHttpException(
                    QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only .xlsx files are accepted");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !XLSX_MEDIA_TYPE.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            throw new QuestionImportHttpException(
                    QuestionErrorCode.QUESTION_IMPORT_FILE_INVALID,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported media type");
        }
    }
}
