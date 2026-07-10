package com.hhtuann.backend.question.api;

import com.hhtuann.backend.question.application.QuestionService;
import com.hhtuann.backend.question.dto.QuestionDetailResponse;
import com.hhtuann.backend.question.dto.UpdateQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single-question REST endpoints (teacher edit).
 * <ul>
 *   <li>{@code GET  /api/questions/{id}} — question detail (latest version + options + answerKey).</li>
 *   <li>{@code PUT  /api/questions/{id}} — edit content/options/answer (type fixed).</li>
 * </ul>
 * Authorization (QUESTION_CREATE + bank ownership) is enforced in {@link QuestionService}.
 */
@RestController
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping("/api/questions/{id}")
    public QuestionDetailResponse getQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return questionService.getQuestionDetail(Long.valueOf(jwt.getSubject()), id);
    }

    @PutMapping("/api/questions/{id}")
    public QuestionDetailResponse updateQuestion(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return questionService.editQuestion(Long.valueOf(jwt.getSubject()), id, request);
    }
}
