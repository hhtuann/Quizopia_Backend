package com.hhtuann.backend.question.api;

import com.hhtuann.backend.question.application.QuestionBankService;
import com.hhtuann.backend.question.dto.CreateQuestionBankRequest;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.question.dto.QuestionBankListItem;
import com.hhtuann.backend.question.dto.QuestionBankResponse;
import com.hhtuann.backend.question.dto.QuestionSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question Bank REST endpoints (Batch B1). Three endpoints only:
 * <ul>
 *   <li>{@code POST /api/question-banks} — create bank</li>
 *   <li>{@code GET /api/question-banks/my} — list owned banks</li>
 *   <li>{@code GET /api/question-banks/{bankId}/questions} — list questions</li>
 * </ul>
 * Fine-grained authorization (ownership, school-scope, teacher profile) is
 * enforced in {@link QuestionBankService}.
 */
@RestController
@RequestMapping("/api/question-banks")
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    public QuestionBankController(QuestionBankService questionBankService) {
        this.questionBankService = questionBankService;
    }

    @PostMapping
    public ResponseEntity<QuestionBankResponse> createBank(
            @Valid @RequestBody CreateQuestionBankRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        QuestionBankResponse response = questionBankService.createBank(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my")
    public PageResponse<QuestionBankListItem> myBanks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long subjectId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return questionBankService.listMyBanks(userId, search, subjectId, page, size, sort);
    }

    @GetMapping("/{bankId}/questions")
    public PageResponse<QuestionSummary> listQuestions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long bankId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        Long userId = Long.valueOf(jwt.getSubject());
        return questionBankService.listQuestions(userId, bankId, type, search, status, page, size);
    }
}
