# Quiz Markdown → Question Bank Mapping (P2-M1-D, Issue #9)

Status: **Implemented (P2-C, issue #18 + issue #10 resolution).** The adapter,
API and persistence live in `MarkdownQuestionImportService` /
`QuestionImportController.importMarkdownQuestions`
(`POST /api/question-banks/{bankId}/questions/import-markdown`) — see
[ADR-003](adr/ADR-003-p2-c-markdown-question-import.md) for the authorization
boundary, the bank-contract preflight and the atomic all-or-nothing import
decision. The mapping below remains the authoritative field-mapping reference.

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
| `numericAnswer` | `QuestionVersion.answerKey.expectedAnswer` | JSONB `{"expectedAnswer": "<4 chars>"}` — identical to `QuestionImportService.buildAnswerKey`; options list empty for NUMERIC_FILL. Note: the Markdown token grammar accepts `-.50`, which editing/publish validation rejects — see the cross-layer table below. |

## Metadata NOT present in Markdown (adapter must not invent values)

| Field | Current default | Resolved (P2-C, ADR-003) |
|---|---|---|
| Question code | generated on create | `QU` + 8 digits, same generator/scope as the Excel import. |
| `difficulty` | `MEDIUM` | Grammar has no difficulty markers; imports default to MEDIUM (editable after import). |
| `defaultPoints` | `1.0` | Not in grammar; constant 1.00 like the Excel import. |
| Bank identity / ownership | n/a | Workspace boundary (ADR-003 §2.2): creator equality + `requireResourceManage` + ACTIVE bank. |
| `status` | ACTIVE | Import creates ACTIVE questions like the Excel path (this doc previously said "draft" — that was drift; the code has set ACTIVE since the Excel import shipped). |
| `metadata` JSONB | `{}` | Reserved, stored `{}`. |

## Cross-layer compatibility — RESOLVED by issue #10 (ADR-003 §2.1)

The pure parser keeps the donor grammar; the **import adapter preflight**
(`MarkdownQuestionImportService.bankContractErrors`) enforces the established
Question Bank / Exam publish contract before anything is persisted, with
actionable per-question diagnostics and **no silent rewriting**:

| Rule | Markdown grammar | Import preflight (P2-C) | Bank edit / Exam publish | Excel import |
|---|---|---|---|---|
| MULTIPLE_CHOICE min correct | ≥ 1 | **≥ 2** (`MULTIPLE_CHOICE_MIN_TWO_CORRECT`) | ≥ 2 | ≥ 2 |
| `-.50` | parses | **rejected** (`NUMERIC_ANSWER_BANK_CONTRACT` — "write `-0.5`, not `-.5`") | rejected | rejected |
| `2,50` | rejected by grammar | rejected (`NUMERIC_ANSWER_INVALID`) | rejected | **accepted** — Excel-only legacy input normalization to `2.50` (unchanged, documented) |

Canonical NUMERIC_FILL answer (all persisting layers): exactly 4 characters
matching `^-?[0-9]+([.][0-9]+)?$`. The frontend mirrors the same preflight in
`quizopia_frontend/lib/quiz-markdown/bank-contract.ts` for live pre-confirm
feedback; the backend re-checks authoritatively. E2E round-trip coverage
(Markdown → import → edit → publish, including the `-.50` / one-star MULTIPLE
rejections and verbatim `2.50` through publication) lives in
`MarkdownQuestionImportServiceIntegrationTests`.

### Other documented asymmetries (deliberate)

- **Option count:** the Markdown grammar hard-requires exactly A–D in order —
  narrower than Excel (A–D), the DB (A–F) and Exam publication (4–6); no
  adapter check is needed for Markdown input.
- **Numeric token storage:** the adapter stores the parser's normalized token
  verbatim, like the Excel path ("preserved verbatim, no reparse") — the
  preflight guarantees it already satisfies the canonical bank grammar.
- **Atomicity:** a Markdown document imports all-or-nothing (unlike Excel's
  row-partial success) — see ADR-003 §2.3.

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
  sets), SHA-256 `2616b851854e41ca6ca5dad97c039c80fa38c4a43bb8d975e11a6bdee6cee3a9`,
  byte-identical in both repositories; both harnesses assert the digest.
- Java: `QuizMarkdownConformanceFixturesTest` (72 markdown tests green incl.
  parser suite). TypeScript: `cross-language-conformance.test.ts` (191
  frontend tests green).
- Documented intentional differences: TS exposes partial preview questions and
  source offsets for invalid sources (live preview) while Java is
  all-or-nothing; diagnostic emission order may differ, so fixtures compare
  unordered diagnostic sets.

### Known limitations of the conformance evidence

- Diagnostic-set comparison collapses identical duplicate tuples; emission
  order and human-readable message text are not verified (documented policy).
- No fuzzing or latency benchmark has been run; the fixtures are curated
  cases, not exhaustive input exploration.
- The backend full Testcontainers integration suite was not executed for
  P2-M1-D (targeted markdown suites only; changes were parser/test-scoped).
