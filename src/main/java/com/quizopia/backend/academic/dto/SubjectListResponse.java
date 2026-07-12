package com.quizopia.backend.academic.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/subjects} — a flat list. Subjects-per-school
 * is small (the endpoint feeds a creation dropdown), so pagination is not needed.
 */
public record SubjectListResponse(List<SubjectView> items) {}
