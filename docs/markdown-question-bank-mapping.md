# Quiz Markdown → Question Bank Mapping (P2-M1-D, Issue #9)

Status: **Analysis only — no adapter, persistence, API or migration in P2-M1.**
The mapping below documents how the structured output of the pure Markdown
parser (`com.quizopia.backend.question.domain.markdown`, Backend PR #3) is
expected to feed the existing Question domain in P2-M2. The future adapter
will translate `QuizQuestion` values into the entities below exactly as the
existing Excel import (`QuestionImportService`) does today.

## Structured output contract (parser → adapter)

```
Markdown source → QuizMarkdownParser.parse → QuizMarkdownParseResult
  valid:   Optional<QuizContent(QuizQuestion…)> + zero errors
  invalid: empty + List<QuizMarkdownError(code, questionNumber, line, column)>
```

Each `QuizQuestion` carries: `number`, `type` (reuses the shared
`question.domain.model.QuestionType` enum 1:1), `stemMarkdown` (source-preserving,
`\n`-joined), ordered `QuizOption(label A–D, markdown, correct)`,
`numericAnswer` (exact four-character token, NUMERIC_FILL only, Java
`String.strip()` normalization) and optional `explanationMarkdown`.

The TypeScript engine (`quizopia_frontend` `lib/quiz-markdown`) produces the
equivalent structure (`stem`, `options[].content`, preview source offsets);
canonical cross-language fixtures with SHA-256 parity live in
`src/test/resources/markdown/quiz-markdown-conformance-fixtures.json` and
`lib/quiz-markdown/conformance/quiz-markdown-conformance-fixtures.json`.

## Field mapping (existing domain)

| Markdown structure | Question domain target | Notes |
|---|---|---|
| `type` | `QuestionType` (`question_versions.question_type`) | Exact 1:1; DB CHECK accepts exactly the four grammar values. |
| `stemMarkdown` | `QuestionVersion.content` | Stored verbatim; rendering (bold/italic/code/math) is a P2-M2+ display concern (frontend `MarkdownContent` already renders the safe subset). |
| `explanationMarkdown` | `QuestionVersion.explanation` | Nullable — matches "zero or one `Lời giải:` block". |
| options A–D (`label`, `markdown`, `correct`) | `QuestionOption(optionKey, content, isCorrect, position)` | `position` = 0–3 in authored order; markdown enforces exactly A–D while the DB allows A–F (no conflict for markdown input). |
| `correct` flags (choice/matrix) | `question_options.is_correct` | Same storage the Excel import uses; `answer_key` stays `null` for SINGLE/MULTIPLE/TRUE_FALSE. |
| `numericAnswer` | `QuestionVersion.answerKey.expectedAnswer` | JSONB `{"expectedAnswer": "<4 chars>"}` — identical to `QuestionImportService.buildAnswerKey`; options list empty for NUMERIC_FILL. |

## Metadata NOT present in Markdown (adapter must not invent values)

| Field | Current default | P2-M2 decision needed |
|---|---|---|
| Question code | generated on create | Markdown carries no code; import must generate or derive (`bank-code`-based sequence per Excel behavior). |
| `difficulty` | `MEDIUM` | Grammar has no difficulty markers; default or import-time choice. |
| `defaultPoints` | `1.0` | Not in grammar; per-import default or bank setting. |
| Bank identity / ownership | n/a | Which Question Bank a Markdown import targets is an application-service decision (auth + Workspace membership), deliberately outside the pure parser. |
| `status` | draft lifecycle | Import creates draft questions like Excel import. |
| `metadata` JSONB | `{}` | Reserved. |

## Known validation mismatches (documented, unchanged in P2-M1)

1. **MULTIPLE_CHOICE minimum correct count.** The Markdown grammar (donor
   spec, accepted v1.0) requires **≥ 1** correct option
   (`MULTIPLE_CHOICE_CORRECT_REQUIRED`). Existing Question Bank validation
   requires **≥ 2**: `ExcelQuestionParser` ("requires at least two correct
   answers") and `QuestionService` update path ("exactly 4 options with at
   least 2 correct"). A Markdown document with exactly one correct
   MULTIPLE_CHOICE answer parses as valid but cannot be saved as-is today.
   Resolution options for P2-M2: (a) reject at adapter level with a clear
   diagnostic, (b) loosen import validation, or (c) tighten the grammar.
   Deliberately NOT changed in #9; flagged for Technical Lead decision.
2. **Option count.** DB/Excel accept flexible A–D (Excel) / A–F (DB) shapes;
   the Markdown grammar hard-requires exactly A–D in order. Markdown is
   strictly narrower — no conflict, but adapter error messages should surface
   parser diagnostics, not entity constraint errors.
3. **Numeric answer normalization.** `QuestionService` update path trims and
   re-validates with `^-?[0-9]+([.][0-9]+)?$`-style rules (4 chars); the
   Markdown parser applies Java `String.strip()` + the four-character grammar.
   Grammars agree on accepted tokens (conformance fixtures verify both sides);
   the adapter should store the parser's normalized token verbatim, like the
   Excel path ("preserved verbatim, no reparse").

## Extension point: AI-generated Markdown

The parser is intentionally a pure function `String → QuizMarkdownParseResult`
with no persistence, auth or API dependencies. Any producer — manual typing
(the P2-M1-C editor), file import, or a future AI generator — can submit a
Markdown draft to the same validation pipeline:

```
AI/other producer → Markdown text → QuizMarkdownParser (this repo)
                 → structured questions + diagnostics (frontend preview or backend)
                 → P2-M2 adapter → Question Bank persistence
```

The frontend engine exposes the identical grammar (`analyzeQuizMarkdown`) for
instant editor feedback; the backend parser remains authoritative before any
persistence. No AI functionality is implemented or implied by P2-M1.

## Cross-language conformance evidence

- Shared canonical fixtures: 36 cases (valid structures + invalid diagnostic
  sets), SHA-256 `3baa1a74674b7b4b2448d6910bafd299eaa1710b60c7f0d90a2c183b8be7be03`,
  byte-identical in both repositories; both harnesses assert the digest.
- Java: `QuizMarkdownConformanceFixturesTest` (72 markdown tests green incl.
  parser suite). TypeScript: `cross-language-conformance.test.ts` (191
  frontend tests green).
- Documented intentional differences: TS exposes partial preview questions and
  source offsets for invalid sources (live preview) while Java is
  all-or-nothing; diagnostic emission order may differ, so fixtures compare
  unordered diagnostic sets.
