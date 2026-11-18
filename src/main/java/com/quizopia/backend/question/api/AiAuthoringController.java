package com.quizopia.backend.question.api;

import com.quizopia.backend.question.ai.AiAuthoringService;
import com.quizopia.backend.question.dto.AiAuthorRequest;
import com.quizopia.backend.question.dto.AiAuthorResponse;
import com.quizopia.backend.question.dto.AiAuthoringStatusResponse;
import com.quizopia.backend.question.dto.AiDocumentExtractResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for the Teacher-only AI-assisted Quiz Markdown authoring
 * (Phase 5, issues #112/#113).
 *
 * <p>All three endpoints re-run the deny-by-default import authorization
 * (active TEACHER, QUESTION_CREATE, bank creator, ACTIVE bank) inside
 * {@link AiAuthoringService} BEFORE any request validation, file reading or
 * provider call — the same boundary as the Excel/Markdown imports.
 *
 * <p>Nothing here persists: candidates and extracted documents live in the
 * teacher's browser session; persistence happens only via the existing
 * {@code POST /api/question-banks/{bankId}/questions/import-markdown}.
 */
@RestController
public class AiAuthoringController {

    private final AiAuthoringService authoringService;

    public AiAuthoringController(AiAuthoringService authoringService) {
        this.authoringService = authoringService;
    }

    /** Feature availability for this bank (teacher owner only). */
    @GetMapping("/api/question-banks/{bankId}/ai/authoring")
    public ResponseEntity<AiAuthoringStatusResponse> status(
            @PathVariable Long bankId,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.ok(authoringService.status(userId, bankId));
    }

    /**
     * Extracts plain text from an uploaded TXT / text-PDF / DOCX / PPTX
     * document. Multipart part name is {@code file}; sizes and types are
     * validated AFTER authorization (issue #113: unauthorized uploads are
     * rejected before any byte is parsed).
     */
    @PostMapping(value = "/api/question-banks/{bankId}/ai/documents/extract",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AiDocumentExtractResponse> extractDocument(
            @PathVariable Long bankId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.ok(authoringService.extractDocument(userId, bankId, file));
    }

    /**
     * One multi-turn authoring step: instruction + current editor source +
     * extracted documents + chat history in, complete validated candidate
     * Markdown document out (never persisted).
     */
    // No `consumes` attribute: a non-JSON body fails message conversion and is
    // mapped to 415 by the scoped advice (same rationale as import-markdown).
    @PostMapping("/api/question-banks/{bankId}/ai/author")
    public ResponseEntity<AiAuthorResponse> author(
            @PathVariable Long bankId,
            @RequestBody AiAuthorRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.ok(authoringService.author(userId, bankId, request));
    }
}
