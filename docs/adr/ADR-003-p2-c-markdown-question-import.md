# ADR-003: Teacher-only Quiz Markdown Question Bank Import (P2-C)

- **Status:** Accepted (implementation contract for Quizopia_System issues #18 and #10)
- **Date:** 2026-11-06
- **Repos:** Quizopia_Backend (this repo), quizopia_frontend
- **Depends on:** ADR-001 §2.2 (workspace ownership), P2-M1 Markdown core (PRs #3/#2)

## 1. Context

P2-A/B delivered a pure Quiz Markdown parser (`question.domain.markdown`) and authoring
editor. Issue #18 asks for a **Teacher-only** import into an existing Question Bank,
placed alongside the Excel import. Issue #10 blocks persistence until two validation
contract mismatches are decided:

| Rule | Markdown grammar (donor) | Excel import | Bank edit (`QuestionService`) | Exam publish |
|---|---|---|---|---|
| MULTIPLE_CHOICE min correct | ≥ 1 | ≥ 2 | ≥ 2 | ≥ 2 |
| NUMERIC_FILL token | 4 chars over `[0-9] - .` (accepts `-.50`) | `^-?[0-9]+([,.][0-9]+)?$` (accepts `2,50`, normalizes) | `^-?[0-9]+([.][0-9]+)?$` | same as edit |

The pre-P2-C import path also authorized via the **legacy** TeacherProfile/school-same
checks (`resolveTeacherProfile`, `ownerTeacherId`, `schoolId`), which predate the Phase 1
workspace migration: a self-upgraded teacher without a `TeacherProfile` (who *can* create
a personal-workspace bank, `QuestionBankService.createBank`) was rejected with
`QUESTION_TEACHER_PROFILE_NOT_FOUND`, and a personal bank (`ownerTeacherId = null`) would
NPE inside `requireOwnershipAndSchool`.

## 2. Decisions

### 2.1 Issue #10 — validation contract unification (bank contract stays authoritative)

1. **MULTIPLE_CHOICE requires ≥ 2 correct answers.** The established Question Bank edit +
   Exam publish contract is kept as the default (issue #18 addendum). The pure parser
   grammar is **unchanged** (donor-pure, ≥ 1 stays valid at parse level); the import
   adapter enforces ≥ 2 in a **preflight** with an actionable per-question diagnostic
   (`MULTIPLE_CHOICE_MIN_TWO_CORRECT`). No silent star-adding, no silent rewrite.
2. **Canonical NUMERIC_FILL answer: exactly 4 characters matching
   `^-?[0-9]+([.][0-9]+)?$`** — the grammar shared by bank edit, exam publish and
   grading. `-.50` parses but is **rejected by the import** with
   `NUMERIC_ANSWER_BANK_CONTRACT` ("write `-0.5`, not `-.5`"); the token is stored
   verbatim and never rewritten. `2,50` is rejected by the grammar itself
   (`NUMERIC_ANSWER_INVALID`). The Excel importer's comma leniency (`2,50` → `2.50`) is
   unchanged **Excel-only input normalization** (documented legacy behavior, semantics
   preserved).
3. The frontend mirrors the same preflight (`lib/quiz-markdown/bank-contract.ts`) so the
   teacher sees contract conflicts before confirming; the backend re-checks
   authoritatively. Keeping the check out of the parser keeps P2-M1 pure (issue #10
   isolation) and the 36 cross-language conformance fixtures byte-stable.

### 2.2 Issue #18 — authorization (workspace boundary, not TeacherProfile)

All question-import/edit write paths share `QuestionBankImportAccessGuard`:

1. active `TEACHER` role (deny by default; dual-role STUDENT+TEACHER passes);
2. effective `QUESTION_CREATE` permission;
3. bank exists (404) and is `ACTIVE`;
4. caller is the bank creator (`created_by_user_id`);
5. Phase 1 resource boundary (`WorkspaceAccessService.requireResourceManage`): personal
   workspace → owner; organization → ACTIVE membership (revoked creators denied, P0-2).

Consequences (deliberate contract changes, tested):

- A self-upgraded teacher without a `TeacherProfile` can now import into their own
  personal bank (previously 404 — issue #18 audit: "avoid silently denying newly
  self-upgraded teachers"); foreign banks still 403.
- `QuestionService` (get/edit question) moves to the same boundary so Markdown-imported
  questions on personal banks are editable; legacy school-era banks keep working because
  the V11 backfill resolves `created_by_user_id` from `owner_teacher_id`.
- UI gating stays UX-only; every write path re-authorizes server-side.

### 2.3 Atomicity — a Markdown document is all-or-nothing

Unlike the row-oriented Excel workbook (partial row success), a Markdown document is one
authored artifact previewed as a whole. **Any parse error or bank-contract violation
rejects the entire document** (HTTP 200, `importedRows = 0`, per-question `RowError`s,
nothing persisted). Hard pre-conditions (auth, blank, size, count) are 4xx `ApiError`.
Concurrent code-generation races map to `QUESTION_IMPORT_DUPLICATE_CODE` (409) with full
rollback, identical to the Excel path.

### 2.4 Explicit limits and error mapping

- `MAX_MARKDOWN_CHARS = 262_144` (256 KiB) → 413 `QUESTION_IMPORT_MARKDOWN_TOO_LARGE`
  (client mirrors the cap).
- `MAX_MARKDOWN_QUESTIONS = 200` → 413 `QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS`.
- Blank document → 400 `QUESTION_IMPORT_FILE_INVALID`.
- The endpoint declares **no `consumes`**: a non-JSON body fails message conversion and is
  mapped by the global 400 validation handler. (A `consumes` mismatch fails at handler
  mapping where no controller-scoped advice applies and would surface as a generic 500.)

### 2.5 Persistence conventions (unchanged from Excel)

`QU`-prefixed generated codes, `ACTIVE` status, version 1, `MEDIUM` difficulty, 1.00
points, verbatim `{"expectedAnswer": token}` answer key for NUMERIC_FILL (preflight
guarantees the canonical grammar), options A–D at positions 0–3, `IMPORT_COMPLETED`
notification, list refresh via existing bank question queries.

## 3. Testing evidence required

- Unit: `MarkdownBankContractTest` (contract matrix incl. `-.50` / one-star MULTIPLE).
- Service integration: persistence shape for all four types, atomicity, limits,
  workspace auth matrix, Markdown → import → **edit** → **publish** round trip
  (issue #10 acceptance), duplicate/constraint behavior.
- API integration: full HTTP auth matrix (unauth, STUDENT-only, ACADEMIC_ADMIN,
  QUESTION_CREATE-without-TEACHER, unrelated teacher, revoked member, no-profile
  personal bank, archived/missing bank), body validation, diagnostics readback,
  Excel import regression suites stay green.
- Frontend: bank-contract unit tests, import component flow (confirm gating, atomic
  rejection, cache invalidation, error mapping), two-import-method coexistence on the
  bank detail page.

## 4. Known limitations

- The Markdown grammar still accepts (at parse level) constructs the bank rejects; the
  import surfaces them as preflight diagnostics instead of changing the donor grammar
  (deliberate; see §2.1).
- Excel-only comma-decimal leniency remains (`2,50` → `2.50`); unifying it would change
  existing Excel behavior, out of P2-C scope.
- Teacher-layout role gating (`TEACHER`/`ACADEMIC_ADMIN`) still renders the page for an
  ACADEMIC_ADMIN; the import actions fail server-side with 403 (permission check) —
  consistent with the existing Excel import behavior.
