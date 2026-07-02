package com.hhtuann.backend.exam.api;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.dto.ExamPurposeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exam-purposes")
public class ExamPurposeController {

    private final ExamService examService;

    public ExamPurposeController(ExamService examService) {
        this.examService = examService;
    }

    @GetMapping
    public Map<String, List<ExamPurposeResponse>> listPurposes(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return Map.of("items", examService.listPurposes(userId));
    }
}
