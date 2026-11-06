package com.quizopia.backend.question.dto;

/**
 * Request body for {@code POST /api/question-banks/{bankId}/questions/import-markdown}
 * (P2-C, issue #18): a single Quiz Markdown document authored in the teacher
 * authoring pane. Parsed by the authoritative Java {@code QuizMarkdownParser}
 * before any persistence.
 *
 * @param markdown raw Quiz Markdown source (CRLF or LF, Vietnamese markers)
 */
public record ImportMarkdownRequest(String markdown) {
}
