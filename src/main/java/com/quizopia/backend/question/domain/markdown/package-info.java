/**
 * Pure Quiz Markdown parser and validator (Phase 2, P2-M1-A — Quizopia_System issue #6).
 *
 * <p>Ports the donor implementation from {@code hhtuann/Quizopia_2.0} (branch {@code develop},
 * commit {@code b8131fbbff8a615cce1ca2431b1875fac50b9ef1},
 * {@code services/quiz-service/src/main/java/com/quizopia/quiz/domain/markdown/}) into the
 * Quizopia System question domain. The grammar is defined by the donor specification
 * {@code docs/specifications/quiz-markdown-spec.md} (Accepted v1.0) and
 * {@code docs/decisions/ADR-013-numeric-fill-format.md}; it is preserved verbatim.
 *
 * <h2>Contract</h2>
 *
 * <pre>{@code
 * Markdown source → QuizMarkdownParser.parse(String) → QuizMarkdownParseResult
 *   valid:   Optional<QuizContent> + no errors
 *   invalid: empty Optional       + List<QuizMarkdownError(code, questionNumber, line, column, message)>
 * }</pre>
 *
 * <p>Each {@link QuizQuestion} carries everything a future Question Bank import adapter needs:
 * question {@code number}, {@link com.quizopia.backend.question.domain.model.QuestionType type}
 * (the four grammar types map 1:1 to the existing enum), source-preserving {@code stemMarkdown},
 * ordered {@link QuizOption options} with {@code correct} indicators (leading {@code *} marker),
 * the exact four-character {@code numericAnswer} for {@code NUMERIC_FILL}, and optional
 * {@code explanationMarkdown}. Content strings are joined with {@code \n}; authored Markdown is
 * preserved as submitted and never re-rendered.
 *
 * <p>The package is dependency-free apart from the shared {@code QuestionType} enum: no JPA
 * entities, repositories, services, REST, security, or Quiz-version/publication lifecycle. It
 * creates no database records and is intended to be reused by a future Question Bank Markdown
 * import application service (P2-M2).
 *
 * <h2>Known specification discrepancy — display math (needs Technical Lead decision)</h2>
 *
 * <p>The donor specification marks the FE-05 math extension (inline {@code $...$} and display
 * {@code $$...$$} delimiters) as <em>proposed / pending project-leader approval</em>, but the
 * donor parser implements and tests standalone display-math blocks: a line that is exactly
 * {@code $$} inside stem/option/explanation starts a literal block whose content is not scanned
 * for structural markers, and an unterminated block reports {@code UNCLOSED_MATH_BLOCK}. This
 * port preserves that tested behavior (including the {@code UNEXPECTED_CONTENT} diagnostic for
 * display math at invalid structural positions) rather than silently redefining the grammar.
 * Whether FE-05 is accepted into the Quizopia System grammar remains an explicit product
 * decision for the Technical Lead; inline math is not interpreted by this parser.
 */
package com.quizopia.backend.question.domain.markdown;
