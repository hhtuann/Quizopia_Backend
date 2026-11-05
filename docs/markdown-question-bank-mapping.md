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
| `numericAnswer` | `QuestionVersion.answerKey.expectedAnswer` | JSONB `{"expectedAnswer": "<4 chars>"}` — identical to `QuestionImportService.buildAnswerKey`; options list empty for NUMERIC_FILL. Note: the Markdown token grammar accepts `-.50`, which editing/publish validation rejects — see the cross-layer table below. |

## Metadata NOT present in Markdown (adapter must not invent values)

| Field | Current default | P2-M2 decision needed |
|---|---|---|
| Question code | generated on create | Markdown carries no code; import must generate or derive (`bank-code`-based sequence per Excel behavior). |
| `difficulty` | `MEDIUM` | Grammar has no difficulty markers; default or import-time choice. |
| `defaultPoints` | `1.0` | Not in grammar; per-import default or bank setting. |
| Bank identity / ownership | n/a | Which Question Bank a Markdown import targets is an application-service decision (auth + Workspace membership), deliberately outside the pure parser. |
| `status` | draft lifecycle | Import creates draft questions like Excel import. |
| `metadata` JSONB | `{}` | Reserved. |

## Cross-layer compatibility differences (verified against origin/main @ 9a43e46; tracked in Quizopia_System issue #10)

The Markdown grammar does **not** currently agree with the existing Question
Bank editing, Excel Import and Exam publication rules. A Markdown source that
the parser accepts is **not automatically importable or publishable**. These
are facts of the current code, not decisions — grammar and validation are
unchanged in P2-M1; unification is tracked in
[Issue #10](https://github.com/hhtuann/Quizopia_System/issues/10) and decided
in P2-M2. (Note: this table reflects this branch's snapshot of `origin/main`;
the concurrently running Phase 1 worktree may introduce further changes that
must be re-audited at merge time.)

| Rule | Markdown<br>(`QuizMarkdownValidator` / `QuizNumericAnswer`) | Excel Import<br>(`ExcelQuestionParser`) | Question editing<br>(`QuestionService`) | Exam publication<br>(`ExamService.validatePublishedQuestionType`) |
|---|---|---|---|---|
| MULTIPLE_CHOICE min correct | **≥ 1** (`MULTIPLE_CHOICE_CORRECT_REQUIRED`) | **≥ 2** ("at least two correct answers") | **≥ 2** ("exactly 4 options with at least 2 correct") | **≥ 2** (`correct < 2` → `EXAM_VALIDATION_ERROR`) |
| Choice option count | exactly A–D in order | exactly A–D | exactly 4 options | 4–6 options |
| TRUE_FALSE_MATRIX option count | exactly A–D | exactly A–D (T/F strings) | exactly 4 | exactly 4 |
| Numeric answer token | 4 chars over `[0-9]`, `-`, `.`; `-` only first; `.` not first/last; surrounding Java `strip()` whitespace | `^-?[0-9]+([,.][0-9]+)?$` + exactly 4 chars; comma normalized to dot | `^-?[0-9]+([.][0-9]+)?$` + exactly 4 chars | `^-?[0-9]+([.][0-9]+)?$` + exactly 4 chars |
| `-.50` accepted? | **yes** (fixture `numeric-negative-and-leading-dot`) | no | **no** | **no** |
| `2,50` accepted? | no (comma not in grammar) | **yes** (normalized to `2.50` on import) | **no** | **no** |

Consequences, explicitly:

1. **MULTIPLE_CHOICE:** a Markdown question with exactly one correct option
   parses as valid (`MULTIPLE_CHOICE_CORRECT_REQUIRED` only fires at zero)
   but is rejected by Excel Import validation, question editing and Exam
   publication today.
2. **`-.50`:** Markdown-accepted, downstream-rejected everywhere
   (`-?[0-9]+` requires at least one leading digit before the decimal
   point in the other three layers).
3. **`2,50`:** Excel Import accepts (and normalizes) a comma decimal that
   the Markdown grammar, question editing and Exam publication all reject.

### P2-M2 adapter obligation (preflight before persistence/publication)

The future Question Bank Markdown adapter must **not** assume
parser-valid ⇒ import/publish-valid. It must re-check every parsed question
against the final agreed domain policy (whatever Issue #10 resolves: unified
grammar, stricter adapter rejection with actionable diagnostics, or relaxed
service validation) before writing `QuestionVersion`/`QuestionOption` rows or
allowing a question into a publishable exam version. Until then, Markdown
authors can hit downstream rejections that the parser cannot currently
predict — surfacing those conflicts early in the editor is a P2-M2 concern.

### Other documented asymmetries (no conflict for Markdown input)

- **Option count:** the Markdown grammar hard-requires exactly A–D in order —
  narrower than Excel (A–D) and the DB (A–F), and narrower than Exam
  publication (4–6). Adapter error messages should surface parser
  diagnostics, not entity constraint errors.
- **Numeric token storage:** the adapter should store the parser's
  normalized token verbatim, like the Excel path ("preserved verbatim, no
  reparse") — but only after the preflight above accepts it.

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

### Known limitations of the conformance evidence

- Diagnostic-set comparison collapses identical duplicate tuples; emission
  order and human-readable message text are not verified (documented policy).
- No fuzzing or latency benchmark has been run; the fixtures are curated
  cases, not exhaustive input exploration.
- The backend full Testcontainers integration suite was not executed for
  P2-M1-D (targeted markdown suites only; changes were parser/test-scoped).
