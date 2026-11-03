# ADR-001: Phase 1 — Public Platform Foundation & Exam/Session Redesign

- **Status:** Accepted (implementation contract for Issues #1 and #2)
- **Date:** 2026-11-02
- **Repos:** Quizopia_System (orchestrator), Quizopia_Backend, quizopia_frontend
- **Decision owners:** Task A (#1) + Task B (#2) joint design — no shared-table change may diverge from this document.

## 1. Context

Quizopia is a school-centric monolith (Java 21 / Spring Boot 4.1 / PostgreSQL / Flyway V1–V9; Next.js 16 frontend).
Phase 1 must transform it into a public, user/workspace-centric platform **and** replace the multi-version
`ExamVersion` model with "one Exam = one immutable published content set", without microservices, without
dropping history, and without editing applied Flyway migrations.

Audit facts at HEAD (backend `main` @ 9a43e46):

- Migration series ends at **V9**. V10+ are free. No offline V10 patch exists at HEAD.
- Tenant boundary is `schools` + `(school_id, id)` composite-FK pattern across V4/V5/V6/V7/V8.
- User↔school attachment is 1:1 via `teacher_profiles` / `student_profiles` (`UNIQUE(user_id)`).
- `exams.current_version_number`, `exam_versions`, `exam_sections.exam_version_id`,
  `exam_questions.exam_version_id`, `exam_sessions.exam_version_id`, `attempts.exam_version_id`
  form the versioned content graph.
- Authorization is service-layer, DB-backed, deny-by-default (`requirePermission(userId, CODE)`);
  there are no `@PreAuthorize` annotations. JWT role claims are untrusted.
- Baseline test run: **1425 tests, 3 pre-existing failures** (documented in §10; unrelated to Phase 1).

## 2. Decision — shared target model

### 2.1 Workspace / Organization / Membership (Task A owns)

```
organizations          1:1 provenance from legacy schools (legacy_school_id UNIQUE, nullable for new orgs)
workspaces             PERSONAL (exactly one per user, partial UNIQUE) | ORGANIZATION (1:1 with organization in Phase 1)
workspace_memberships  (workspace_id, user_id) UNIQUE; role OWNER|ADMIN|EDITOR|MEMBER;
                       status ACTIVE|INVITED|SUSPENDED|REMOVED
```

- `schools` and all profile tables remain untouched — they become an *academic concept* inside an
  organization during transition; nothing is deleted.
- Backfill determinism (V10):
  - 1 organization per school (`legacy_school_id = schools.id`), same code/name/timezone.
  - 1 organization workspace per organization.
  - 1 personal workspace + OWNER ACTIVE membership per existing user.
  - Org memberships from profiles, all ACTIVE: ACADEMIC_ADMIN-with-profile → ADMIN, TEACHER → EDITOR,
    STUDENT → MEMBER. (A user's global roles are unchanged; membership role is workspace-scoped.)
- New user provisioning (registration + admin creation): personal workspace + OWNER membership created
  transactionally and idempotently inside the same transaction as the user row; DB partial unique index
  is the concurrency backstop.

### 2.2 Resource ownership (Tasks A+B shared)

`question_banks`, `exams`, `classrooms`, `exam_sessions` (via exam), `attempts` (via session) gain:

- `workspace_id BIGINT NOT NULL` (added nullable → backfilled → NOT NULL in the same migration).
- `created_by_user_id` (question_banks, exams) — the acting **User**, replacing reliance on TeacherProfile.
- Legacy `school_id` / `subject_id` / `owner_teacher_id` / `student_profile_id` columns become **nullable
  legacy provenance**; existing composite FKs are kept and simply stop applying when the column is NULL.
  They are NOT dropped in Phase 1.
- Code uniqueness moves from `(owner_teacher_id, LOWER(code))` to `(workspace_id, LOWER(code))`
  (new unique indexes; old ones retained — they remain valid for school-era rows).

Subject policy: for ORGANIZATION workspaces, `subject_id` must belong to the org's legacy school
(existing composite FK continues to enforce). For PERSONAL workspaces, `subject_id` is optional (NULL) —
documented compatible adaptation of the school-bound subject constraint.

### 2.3 Identity, roles, modes (Task A)

- A public user registers without school/invite (teacher invite flow retained as optional org onboarding).
- TEACHER/STUDENT remain global capability roles (existing RBAC tables untouched). A user may hold both.
- **Mode (teacher/learner) is a UI preference only** — never sent to the server, never authorized on.
- `EffectiveRoleResolver` single-role collapse stays only where already used; new code must not adopt it.
- New `WorkspaceAccessService` (single implementation, reused by all modules):
  `accessibleWorkspaces(userId)`, `requireActiveMember(userId, workspaceId)`,
  `requireAnyWorkspaceRole(userId, workspaceId, OWNER, ADMIN, …)`; layered on top of existing
  `requirePermission` checks, replacing school-same checks.
- Eligibility (session start / available sessions), jointly with Task B:
  - `PUBLIC` session → any ACTIVE platform user (explicit Phase 1 rule; no longer "same school").
  - `CLASS_RESTRICTED` → user-based membership in an assigned classroom.

### 2.4 Exam model (Task B owns)

```
Exam (DRAFT → PUBLISHED → ARCHIVED)
 ├── exam_sections.exam_id        (composite FK (exam_id, exam_section_id) guarantees same-exam)
 │    └── exam_questions.exam_id  (same-exam composite FK; question snapshots + provenance preserved)
 └── exam_sessions.exam_id        (session-level duration/window/attempts/visibility/lifecycle)
      └── attempts (session-scoped; user_id owner; Exam reached via session)
```

- Content columns move from `exam_versions` onto `exams`: `instructions`, `tf_matrix_scoring`,
  `total_points`, `published_at`, plus `duration_minutes` retained as **editor metadata/default**
  (runtime uses session duration only), `source_exam_id` (duplicate provenance), `workspace_id`,
  `created_by_user_id`.
- `exams.status` CHECK becomes `('DRAFT','PUBLISHED','ARCHIVED')` in the contract migration (V15);
  legacy `READY` maps to `PUBLISHED`.
- Canonical-version rule: for each legacy exam, the **earliest PUBLISHED version (by version_number)
  keeps the original exam id**; if none published, the earliest DRAFT does. Every other version becomes
  a new independent exam. `legacy_exam_content_map` records the full mapping immutably.
- Session duration: materialized as `COALESCE(session.duration_minutes, version.duration_minutes)`
  during backfill; afterwards the runtime reads **session duration only** (0 = unlimited, capped by ends_at).
- `exam_versions` table is retained (orphaned, no dependents) after cutover for audit; dropped in a
  later release only after production verification.
- Attempt ownership: `attempts.user_id` added (Task A migration), backfilled from `student_profiles.user_id`;
  `student_profile_id` retained as legacy provenance; `exam_version_id` dropped in V15.

## 3. Decision — migration series (forward-only, ordered, jointly owned)

| Version | Task | Content |
|---|---|---|
| **V10** | A | `organizations`, `workspaces`, `workspace_memberships` + full backfill + verification DO-blocks |
| **V11** | A | `question_banks`/`exams`: add `workspace_id`, `created_by_user_id`; make school/subject/owner nullable; backfill; new unique indexes |
| **V12** | A | `classrooms.workspace_id`; `classroom_members.user_id` (+nullable legacy cols, backfill, unique); `attempts.user_id` backfill |
| **V13** | B | Exam expand: content columns on `exams`; `exam_id` on sections/questions/sessions (nullable); `legacy_exam_content_map`; nullable school/owner on sessions |
| **V14** | B | Exam backfill + cutover: canonical split, section/question/session repoint by exact version id, duration materialization, status mapping, NOT NULL + FKs + uniques, anomaly preflight (fail, never reassign silently) |
| **V15** | B | Contract: drop `exam_version_id` columns & their FKs, `exams.current_version_number`, version-scoped uniques; new `chk_exams_status`; keep `exam_versions` table |

Rules: no edits to V1–V9; every migration is a single transaction with verification DO-blocks that
`RAISE EXCEPTION` on orphan/inconsistent data (fail preflight instead of guessing); rerunnable in
staging via `pg_dump` restore (see runbook §8).

Deployment order: **A (V10–V12) → verify → B (V13–V15) → verify.** Application releases are deployed
together with their migration set; B's application requires A's schema.

## 4. ID mapping contract (old → new)

| Legacy | Target | Mechanism |
|---|---|---|
| `school_id` | `workspace_id` (org workspace) | V10 backfill; `organizations.legacy_school_id` + `workspaces.organization_id` |
| `owner_teacher_id` | `created_by_user_id` | V11 backfill via `teacher_profiles.user_id` |
| `student_profile_id` | `user_id` | V12 backfill via `student_profiles.user_id` (classroom_members, attempts) |
| `exam_version_id` | `exam_id` | V14 backfill via `legacy_exam_content_map` |
| — | `workspace_id` | V11/V13 backfill from school → org workspace |

## 5. API contract

Task A:
- `GET /api/workspaces/my` — workspaces the caller can activate (id, type, name, role, status; never other users' personal workspaces).
- `POST /api/question-banks` accepts `workspaceId`; personal workspace requires no school/subject.
- Existing endpoints keep working for school-era clients (school columns auto-derived during transition where needed).

Task B (nested canonical; legacy adapters delegate to the same services and validate `session.examId == path.examId`):
```
POST/GET /api/exams · GET/PUT /api/exams/{examId} · PUT /api/exams/{examId}/composition
POST /api/exams/{examId}/publish|duplicate|archive
GET/POST /api/exams/{examId}/sessions · GET/PUT /api/exams/{examId}/sessions/{sessionId}
POST /api/exams/{examId}/sessions/{sessionId}/schedule|open|close|cancel
GET   /api/exams/{examId}/sessions/{sessionId}/results|statistics|results/export
```
`/api/exam-sessions/{sessionId}/...` legacy routes are retained temporarily (student entry points,
mobile clients) and delegate to identical services. Version-specific endpoints
(`POST /api/exams/{id}/versions`, `expectedVersionNumber`) are removed with the versioned domain.

## 6. Frontend contract

- Active Workspace switcher + Teacher/Learner mode in Header; persisted as UI preference (localStorage);
  never sent as authorization.
- `/exams/[examId]` becomes the management home with tabs Overview / Questions / Sessions;
  `/exams/[examId]/sessions/new` and `/exams/[examId]/sessions/[sessionId]` nested; legacy
  `/exam-sessions/*` teacher pages redirect; student entry points unchanged.
- No version list / create-draft-version / publish-version UI remains.

## 7. Ownership of files

- Task A: migrations V10–V12; `workspace` module (new); RegistrationService; WorkspaceAccessService;
  QuestionBank/Classroom service auth paths; frontend workspace switcher.
- Task B: migrations V13–V15; `exam`/`attempt` module refactor; nested controllers; frontend exam UX.
- Shared: this ADR; `docs/runbooks/phase1-migration.md`; issue checklists.

## 8. Deployment / recovery runbook

1. Backup: `pg_dump -Fc quizopia_db > pre_phase1_$(date +%Y%m%d%H%M).dump` (and verify restore into a scratch DB).
2. Baseline counts/checksums (SQL in runbook): users, schools, profiles, banks, questions, exams, versions,
   sections, questions(exam), sessions, attempts, answers, grades, grade_items.
3. Deploy Task A release (V10–V12); run verification SQL (row-count preservation, one personal workspace
   per user, membership completeness).
4. Deploy Task B release (V13–V15); run verification SQL (mapping completeness, session/attempt lineage,
   grade totals unchanged, duration materialization).
5. Recovery: restore backup + redeploy previous release. No reverse migrations are provided by design.

## 9. Risks & mitigations

- **Composite-FK nullability**: making leading columns nullable disables those FKs for new rows —
  mitigated by new workspace-scoped FKs and service-layer checks.
- **Code collisions** on workspace-scoped unique during backfill: pre-existing data satisfies
  `(school→workspace, owner, code)` uniqueness, so `(workspace, code)` is satisfiable; generator retries on conflict.
- **Legacy draft sessions pointing at DRAFT versions** (illegal state): preflight counts them; if any exist,
  V14 raises with details (fail over silent reassignment).
- **PUBLIC semantics widening** (school → platform): accepted Phase 1 rule per issue #1 §3.7; documented here.

## 10. Baseline test evidence (pre-change)

`mvnw test` on backend main @ 9a43e46 — 1425 run, 3 failures, 4 skipped. Pre-existing failures (not
attributable to Phase 1 work, to be re-checked at the end):
1. `StudentOnboardingIntegrationTests.assignStudentCreatesProfileWithAutoCode` — expects `STU` code pattern `[A-Z]{1,3}\d{4}`, actual `STU00000001`.
2. `AttemptDetailAndMyQueryCountIntegrationTests.detailQueryCountIsConstantAcrossN` — expected 7 queries, actual 8.
3. `QuestionBankApiIntegrationTests.getImportTemplate_teacherWithPermission_returns200AndValidXlsx` — expected 18 rows, actual 9.

## 11. Implementation addendum (decisions taken during Task A)

1. **Transitional ownership fill (V11/V12):** BEFORE INSERT OR UPDATE triggers on `question_banks`,
   `exams`, `classrooms`, `classroom_members`, `attempts` fill the new workspace/user ownership columns
   from legacy provenance **only when the writer omits them**. JPA always writes the columns explicitly,
   so the triggers are dormant in application code; they exist so legacy-style fixtures/writers keep
   working during the expand→contract transition. `quizopia_org_workspace_for_school()` lazily
   provisions the organization + workspace pair (mirroring the V10 backfill) for schools created after
   migration. All triggers are dropped in the Task B contract phase.
2. **Java-side resolver:** `OrganizationWorkspaceResolver` provides the same lazy provisioning for
   service paths (ExamService, ClassroomService, QuestionBankService, DemoDataSeeder);
   `StudentOnboardingService.assignStudentToSchool` additionally grants ACTIVE MEMBER org-workspace
   membership.
3. **organizations.legacy_school_id FK is ON DELETE SET NULL** — deleting a legacy school detaches the
   organization (which survives as a standalone org) instead of blocking the delete.
4. **Teacher invite is optional at registration** (issue #1 A2): a supplied-but-wrong code still yields
   `AUTH_TEACHER_INVITE_INVALID`; an absent code now succeeds. A new public user can self-select the
   teacher/creator capability and author personal question banks without any school or invite.
5. **Question-bank/exam code uniqueness** moves to `(workspace_id, created_by_user_id, LOWER(code))`,
   which is exactly the legacy `(owner_teacher_id, LOWER(code))` guarantee re-expressed per workspace.
6. **First-level-cache caveat:** test fixtures that persist legacy-shape entities and then call services
   in the same transaction must use explicit ownership (helpers) or clear the persistence context —
   the DB-level fill is invisible to a cached JPA instance.

## 12. Issue #4 P0 addendum (user-based attempts + membership revocation)

1. **User-based attempts (P0-1, V16):** `attempts.user_id` is the primary identity (mandatory since
   V12; uniqueness moved to `(exam_session_id, user_id, attempt_number)`, partial
   one-active-per-`(session,user)`, and per-user submit key). `student_profile_id`/`school_id` are
   nullable legacy provenance. Attempt capability = holding the effective attempt PERMISSION
   (no role gate, no StudentProfile requirement); the TEACHER role is granted
   ATTEMPT_START/ATTEMPT_ANSWER_SAVE/ATTEMPT_SUBMIT so one account can be creator and learner
   (dual capability, no admin powers). Profile enrollment status no longer gates PUBLIC attempts;
   CLASS_RESTRICTED still requires user-based assigned-class membership. The realtime envelope
   carries `userId` (primary) plus nullable `studentProfileId` provenance.
2. **Resource-boundary policy (P0-2):** centralized in `WorkspaceAccessService.requireResourceRead /
   requireResourceManage / accessibleWorkspaceIds`. In ORGANIZATION workspaces, creation-time
   ownership NEVER overrides a revoked membership — the creator needs an ACTIVE membership for
   reads (404 anti-enumeration where reads used it) and ACTIVE membership for writes (403);
   PERSONAL workspaces keep the authenticated true owner. Applies uniformly to Question Bank,
   Classroom, Exam, Session (incl. nested routes, lifecycle, classes, roster), results/statistics/
   export, and the WebSocket monitor topic.
3. **Lazy org provisioning backfills memberships (bug fix):** `OrganizationWorkspaceResolver`
   now inserts the V10-rule memberships (teacher→EDITOR, student→MEMBER, academic-admin→ADMIN)
   for a school's existing profile holders whenever it lazily provisions an organization
   workspace; previously post-migration schools had no membership rows.
4. **Baseline note:** the third pre-existing baseline failure (detail query count 7-vs-8) passes
   under the user-based model — the profile lookup is gone (stable count 6). The other two
   baseline failures remain unchanged (see §10).

## 13. Issue #4 P1 checkpoint A — completion evidence (2026-11-03)

**Checkpoint A: complete.** Independent review outcome supplied in the checkpoint handoff:
**READY**, after the shared ordered-CTE lifecycle refresh fix. This records checkpoint A only;
checkpoints **B/C remain open and unverified**. No backend plan document exists to synchronize.

### Decisions verified

- **Duplicate grading policy:** duplication previously reset nondefault `tfMatrixScoring` to the
  draft default. `ExamService.duplicateExam` now copies the source policy through
  `Exam.copyGradingPolicyFrom`, using a deep JSON copy. Regression coverage checks independent
  policy/content JSON, preserved snapshots/provenance, an untouched source, no copied sessions or
  attempts, and draft totals recomputed at publish.
- **Nested session-list scope:** the nested list previously reused the creator-only list and hid
  other creators' sessions. `listSessionsForExam` now enforces permission and parent-exam workspace
  read access, then lists that exam's sessions across creators with search/status filters,
  pagination and sorting. Outsiders and revoked members remain denied; legacy
  `GET /api/exam-sessions/my` remains creator-only, including with an `examId` filter.
- **Shared lifecycle lock protocol:** both creator-scoped and exam-scoped lists refresh effective
  lifecycle state through the same materialized eligible-row CTE, locking rows in ascending ID
  order before updating them. Neither path uses `SKIP LOCKED`. The concurrency regression covers
  overlapping entry points, contention on the lowest ID, lifecycle timestamps and repeat-refresh
  idempotence.

### Verification record

Reproduction command (run from `backend`):

```powershell
.\mvnw.cmd test "-Dtest=P1CheckpointAIntegrationTests,ExamServiceIntegrationTests,ExamSessionServiceIntegrationTests,ExamSessionListConcurrencyIntegrationTests,RevokedMembershipExamSessionIntegrationTests"
```

Existing `target/surefire-reports/com.quizopia.backend.exam.<suite>.txt` and matching
`TEST-com.quizopia.backend.exam.<suite>.xml` were inspected for all five suites:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `P1CheckpointAIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamServiceIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamSessionServiceIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamSessionListConcurrencyIntegrationTests` | 1 | 0 | 0 | 0 |
| `RevokedMembershipExamSessionIntegrationTests` | 11 | 0 | 0 | 0 |
| **Total** | **75** | **0** | **0** | **0** |

The XML `test` property confirms the same five-suite selection (in a different order).
This documentation review inspected existing results; it did not rerun Maven or establish a
full-suite result.

Failure-to-pass trail:

- Initial regression artifact: temporary `p1-a-before.log` under
  `C:\Users\GIGABYTE\AppData\Local\Temp\opencode` — inspected summary: **16 tests, 12 failures,
  0 errors, 0 skipped**, `BUILD FAILURE`.
- Intermediate runs: the checkpoint handoff reports 70/68-case stages with SQL and then
  creator-filter failures, subsequently resolved. Those intermediate logs were not available in
  the reviewed artifacts; they are historical context, not the completion gate.
- Final completion gate: the five inspected Surefire suites above, **75/75 passing**, plus the
  handoff's independent **READY** review after the shared ordered-CTE fix.

**Follow-ups:** verify checkpoints B/C separately and re-check the remaining baseline failures
from §10 at the broader verification gate. Migration decisions/history in §§3 and 11–12 remain
intact; checkpoint A introduces no migration-history change.

## 14. Issue #4 P1 checkpoint B — implementation evidence (2026-11-03)

**Checkpoint B: implemented and targeted verification passed; independent review pending.**
Starting backend commit: `2aec8ee` (checkpoint A). No B commit or push has been made.
Checkpoint C (broader backend/baseline and frontend/CI evidence) remains open.

### Metadata contract and root cause

`PUT /api/exams/{examId}` previously accepted no body and called `getExamDetail`, so
successful responses did not mutate anything. It now requires `EXAM_UPDATE`, the existing
workspace management policy and a locked Exam row, then validates `expectedExamVersion`
against the JPA optimistic version. Only `title` (required, nonblank, ≤255) and `description`
(nullable, ≤2000; omitted/null clears it) are replaced. DRAFT and PUBLISHED permit these
display fields; ARCHIVED is read-only (409). Unsupported fields are rejected (400), including
content, grading and ownership fields. Instructions, editor duration, grading policy, points,
composition, provenance and lifecycle are not part of this request. Identical replacements
also consume the token; stale tokens return `EXAM_CONCURRENT_MODIFICATION` (409).

Workspace behavior reuses the existing policy: an organization creator must remain ACTIVE;
another active member needs management authority (OWNER/ADMIN); a personal workspace's
authenticated true owner remains allowed. Metadata writes do not modify compositionRevision.

### Fresh failing-before / passing-after evidence

Before changing production code, the new metadata suite ran against A with:

```powershell
.\mvnw.cmd test "-Dtest=P1CheckpointBMetadataIntegrationTests"
```

Result: **13 tests, 13 failures, 0 errors, 0 skipped**. Full captured output:
`C:\Users\GIGABYTE\.local\share\opencode\tool-output\tool_19e0e0682001HxLaKCPuqNgrMm`.
Failures included unchanged title, accepted invalid/unsupported bodies, read instead of write
authorization and successful archived requests. An intermediate post-fix run was 12/13 passing:
the admin test changed a membership via JDBC after JPA cached it; clearing the test persistence
context made the new role visible. No authorization policy was relaxed.

Final command (backend root, Docker available, PostgreSQL 17.11):

```powershell
.\mvnw.cmd test "-Dtest=P1CheckpointBMetadataIntegrationTests,P1CheckpointBMetadataConcurrencyIntegrationTests,V14ExamSplitMigrationStagedFlywayTest,V17UserBasedParticipantsStagedFlywayTest,P1CheckpointAIntegrationTests,ExamServiceIntegrationTests,ExamCompositionServiceIntegrationTests,ExamPublishServiceIntegrationTests,ExamPublishConcurrencyIntegrationTests,ExamSessionServiceIntegrationTests,ExamSessionListConcurrencyIntegrationTests,RevokedMembershipExamSessionIntegrationTests" "-Dspring.jpa.show-sql=false" "-Dlogging.level.org.hibernate.SQL=INFO"
```

Fresh Surefire TXT summaries inspected for exactly these suites:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `P1CheckpointBMetadataIntegrationTests` | 15 | 0 | 0 | 0 |
| `P1CheckpointBMetadataConcurrencyIntegrationTests` | 1 | 0 | 0 | 0 |
| `V14ExamSplitMigrationStagedFlywayTest` | 1 | 0 | 0 | 0 |
| `V17UserBasedParticipantsStagedFlywayTest` | 1 | 0 | 0 | 0 |
| `P1CheckpointAIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamServiceIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamCompositionServiceIntegrationTests` | 18 | 0 | 0 | 0 |
| `ExamPublishServiceIntegrationTests` | 13 | 0 | 0 | 0 |
| `ExamPublishConcurrencyIntegrationTests` | 1 | 0 | 0 | 0 |
| `ExamSessionServiceIntegrationTests` | 21 | 0 | 0 | 0 |
| `ExamSessionListConcurrencyIntegrationTests` | 1 | 0 | 0 | 0 |
| `RevokedMembershipExamSessionIntegrationTests` | 11 | 0 | 0 | 0 |
| **Total** | **125** | **0** | **0** | **0** |

The metadata concurrency test uses independent committed transactions: exactly one writer with
the same token succeeds and the other returns the concurrency conflict; persisted version is 1.
Clean-slate Spring contexts migrated V1→V17 successfully. This is targeted evidence only; the
three §10 baseline cases were not rerun in B and are explicitly deferred to C.

### Migration verification and operational boundary

Added `docs/runbooks/phase1-migration.md` and four standalone read-only SQL artifacts under
`docs/runbooks/sql/p1-b-*.sql`. No V1–V17 script was edited. The V14 tautology remains immutable
history and is superseded as verification evidence by independent before/after captures.

The staged V14 test now runs those actual scripts at V12/V17 and compares each original ID,
normalized JSONB checksum **and full payload**: original Exam metadata, per-version mapped
Exam content, retained audit versions, sections/questions/options, Session duration and exact
version lineage, Attempts, AttemptQuestions, Answers, Grades and GradeItems. Existing canonical
mapping/duration/count assertions remain. A deliberate score change with unchanged row counts
is detected, then restored and compared again. Flyway validation passes after cutover.
The same fixture probes a session on a DRAFT version and a corrupted orphan section; both
fail the migration and leave the mapping table empty before the valid migration succeeds.

This is deterministic fixture proof, not production-data proof. Production requires a verified
backup/restore, stopped writers, retained preflight export at V12, exact postflight comparison
at V17, and recovery by backup restore on any mismatch. Existing upgraded environments without
a pre-upgrade export must recover the baseline from their backup. No security/migration/data-loss
blocker was observed in the executed scenarios. Independent review and checkpoint C remain
required before merge readiness can be assessed.

### B independent-review remediation — fresh verification (2026-11-03)

The first independent review was **NOT READY** with two MEDIUM evidence gaps. The initial
125-test result above was real, but its migration coverage was narrower than described:
`exam_metadata` excluded noncanonical split exams, while `exam_content` checked only content
settings; the options export compared an empty fixture. That first run did not prove split
metadata/ownership or populated option preservation.

Remediation is limited to verification SQL, the staged fixture and these documents:

- Added `exam_version_metadata` evidence keyed by **each legacy version ID**, comparing the
  full expected/actual Exam row. Expected metadata is derived from the V12 Exam/version using
  V14 rules: canonical keeps original metadata; splits inherit workspace, school, subject,
  teacher, purpose, description and creation time, use the version title (trim/blank fallback)
  and creator (fallback to original creator), append `-V<number>` to code, and initialize JPA
  version/composition revision to zero. Content/status fields are compared too. Generated
  target IDs remain verified through mapping/postflight; only generated split `updated_at`
  is normalized to null. Canonical `updated_at` is still checked exactly.
- Added a split-title mutation assertion **before** changing the SQL. Running
  `.\mvnw.cmd test "-Dtest=V14ExamSplitMigrationStagedFlywayTest"` failed with **1 test,
  1 failure, 0 errors, 0 skipped** at `split title mutation detected`: the old exports remained
  equal despite the mutation. After the SQL fix, isolated probes detect split title, creator,
  description, subject and teacher-provenance mutations, rolling each back to exact equality.
- Seeded **four real exam_question_options** (A/B, correct/incorrect, positions 0/1), two on
  the canonical published question and two on the noncanonical published question. The
  baseline explicitly requires four option evidence entries. Full JSONB comparison preserves
  ID, question link, option key, content, correctness, position and creation time. Independent
  content-change, correctness-change and deletion probes are detected; savepoint rollback
  restores exact payload/checksum equality after the probes.

After these changes, the **same complete 12-suite command recorded above was rerun fresh**.
All 12 Surefire TXT summaries were inspected: **125 tests, 125 passed, 0 failures, 0 errors,
0 skipped**; per-suite counts remain exactly as in the table. The strengthened V14 staged
test passed (1/1), including existing fail-fast, score-drift and lineage assertions. The first
isolated post-fix staged run also passed 1/1. Mutation probes are assertions within that one
test, not extra test cases. No V1–V17 migration or checkpoint-A implementation was edited.
Independent re-review is pending; this is not a new READY verdict or production-data proof.

## 15. Release follow-up — duplicate purpose response (2026-11-03)

Tech Lead-approved follow-up to [Issue #4 review comment 6594324419](https://github.com/hhtuann/Quizopia_System/issues/4#issuecomment-6594324419):
`duplicateExam` already persisted the source `purposeId`, but its immediate HTTP response
hard-coded `ExamListItem.purpose` to null. It now resolves the copied purpose and uses the
existing nullable `ExamPurposeSummary(id, code, title)` mapping pattern.

The focused MockMvc regression `P1CheckpointAIntegrationTests#duplicateReturnsPersistedPurposeSummary`
seeds a non-null purpose, calls `POST /api/exams/{examId}/duplicate`, verifies HTTP 201,
flushes/clears persistence state, checks the duplicate's stored `purpose_id` through SQL,
and asserts the response purpose ID, code and title.

Executed once with a 180000 ms command timeout:

```powershell
.\mvnw.cmd test "-Dtest=P1CheckpointAIntegrationTests#duplicateReturnsPersistedPurposeSummary" "-Dspring.jpa.show-sql=false" "-Dlogging.level.org.hibernate.SQL=INFO"
```

**BUILD SUCCESS — 1 test, 1 passed, 0 failures, 0 errors, 0 skipped** (Maven 01:06;
Surefire 22.22 s). Evidence: `target/surefire-reports/com.quizopia.backend.exam.P1CheckpointAIntegrationTests.txt`.
This is the tiny release-response correction only: composition/grading, A/B decisions,
controller and migrations are unchanged. Existing uncommitted C files and user-managed
servers were preserved; the prior four concurrency / three admin test evidence was not
rerun or replaced. This follow-up does not change the outstanding integrated release gates.

## 16. Issue #4 P1 checkpoint C - completion evidence

Scope: user-based moderation concurrency, explicit SYSTEM_ADMIN result-access policy
with audit, frontend moderation UI, integrated browser E2E, full-suite regression
and release-gate reconciliation. All on Task B branch atop B commit `f804c92`.

### Production changes
- `attempt/application/SystemAdminResultAccessPolicy.java` (new): explicit DB-backed
  policy - active account + unexpired DB `SYSTEM_ADMIN` grant required for break-glass
  result reads; application clock; publishes a grant-audit event on each allowed read.
- `AttemptResultService`, `SessionResultService`: SYSTEM_ADMIN reads delegate to the
  policy; no role-authority bypass remains.
- `exam/application/ExamService.listMyExams`: null-guard on subject lookup - a
  subjectless personal-workspace exam previously NPE'd (`Map.of().get(null)`) making
  `GET /api/exams/my` 500 (found by browser E2E; regression test in
  `ExamControllerIntegrationTests.getMyExamsWithSubjectlessPersonalExamReturns200AndNullSubject`).
- Frontend `components/teacher/ParticipantModeration.tsx` (new) wired into
  `LiveMonitor.tsx`: explicit by-user Block/Unblock (DRAFT/SCHEDULED/OPEN), roster/
  participant query invalidation, status chip (ELIGIBLE / BLOCKED with preservation note).
- Frontend personal-workspace form fixes: `exams/new` and `question-banks/new`
  default `subjectId: undefined` (was `NaN` which failed zod `.positive()` with the
  field hidden - silent no-op submit; found by browser E2E).

### Test evidence (fresh, on final source)
- Targeted C suites: `UserModerationConcurrencyIntegrationTests` 4/4,
  `SystemAdminResultAccessPolicyIntegrationTests` 3/3, `ExamControllerIntegrationTests`
  16/16 (incl. new D1 regression) - 23/23, BUILD SUCCESS.
- Checkpoint A suites re-run after all edits: 76/76 (P1CheckpointA 22 incl. purpose
  regression, ExamService 21, ExamSessionService 21, Concurrency 1, RevokedMembership 11).
- Full backend suite: **1526 run, 2 failures, 0 errors, 4 skipped** - the only failures
  are the two documented pre-P0 baselines (StudentOnboarding auto-code pattern,
  QuestionBank xlsx template row count); 1480->1526 growth is A/B/C test additions;
  no new regressions.
- Frontend: `tsc --noEmit` clean, `eslint` clean, production build green,
  `smoke-realtime-identity` 12/12 PASS.

### Integrated browser E2E (real servers :3100 frontend standalone / :8181 backend JAR,
real JWT auth via form login, no MockMvc, no mocks)
- Build identity verified: standalone chunks contain `NEXT_PUBLIC_API_URL=http://localhost:8181`
  (2 files); backend PID/JAR confirmed; static assets 58/58 byte-identical.
- Teacher2: exam list (D1 live proof), bank + question authoring via UI, composition,
  publish, session create + schedule. Learner: available sessions -> start attempt ->
  answer -> submit (autosave Live/Saved observed, `/attempts/1` submitted).
- Moderation: `POST /api/exam-sessions/3/participants/by-user/3/block` 200 then
  roster refetch; UI status "User 3: BLOCKED. Existing attempts may still be
  completed."; learner Available-sessions list became empty (block prevents discovery);
  `POST .../unblock` 200 -> "User 3: ELIGIBLE", session reappeared for learner.
- Authorization negative: outsider teacher direct-navigating the session detail got
  `GET /api/exam-sessions/3` 403 with an empty denial page - no moderation controls,
  no roster, no data leak.
- Screenshots/network logs: `C:\Users\GIGABYTE\AppData\Local\Temp\opencode\p1c-e2e\`.

### Release-gate reconciliation (honest status)
- Backend repo has NO CI workflow configured (`gh pr checks` -> none; `gh run list`
  empty). Required before merge approval: add and pass CI on PR #2.
- Frontend Vercel deployment on PR #1: PASS.
- Migration rehearsal on a production backup (freeze writers, restore, preflight/
  postflight SQL equality) is documented in the runbook but NOT yet executed - required
  before production cutover approval.
- The two baseline test failures above remain accepted-known (pre-existing).

## 17. Release hardening � gates evidence (2026-11-03)

Follows the Tech Lead release review (GO for hardening only; merge NO-GO until gates).

### Gate 1 � Backend CI
- Added `.github/workflows/ci.yml` (Java 21 Temurin, Maven cache, `./mvnw -B -ntp verify`,
  surefire artifacts upload). Run history: first run failed on mvnw exec bit
  (fixed in-repo `mode 100755` + chmod guard); second run failed on
  `AttemptConstraintTranslationIntegrationTests.psqlFallbackExtractsConstraintName` �
  its raw JDBC SQL predated the V16/V17 user-based schema (attempts/participants
  inserts omitted `user_id`); fixed the test SQL to the canonical schema
  (CI artifact `surefire-reports` was the evidence; the two pre-existing stale
  test expectations � student auto-code `STU\d{8}` and 9-column xlsx template �
  were aligned in the same push series).
- Local mirror of the CI goal: `mvnw -B -ntp verify` ? tests **1526 run /
  0 failures / 0 errors / 4 skipped** (skip list explained below); the local
  `repackage` step fails only because the user-managed runtime holds the JAR
  (Windows lock) � not a test failure; CI has no such lock.
- Final-HEAD CI run: recorded in the Issue #4 report (run id + URL).

### Gate 2 � Migration verification
- **No production database or pre-upgrade backup exists** � the production-data
  rehearsal of phase1-migration.md CANNOT be executed yet; this sub-gate stays
  open for the first real deployment and must not be claimed as passed.
- Executed on staging instead: automated gate fresh on HEAD
  (`P1CheckpointBMetadataIntegrationTests` 15/15 + both staged Flyway suites
  1/1 + 1/1 = 17/17 BUILD SUCCESS); real `pg_dump -Fc` of the staging DB
  (SHA-256 `8AF2C74092F112E21C833491FDAC4573926C78CB140CFA5B4B0DCB2295BB059F`),
  `pg_restore --exit-on-error` into an independent scratch database, and
  full row-group equality (flyway history checksum, users, exams, sessions,
  attempts, participants � 6/6 identical) between source and restore.
  Scratch DB dropped afterwards; source staging data untouched.
- Clean V1->V17 on empty schema is exercised by the full suite/CI.

### Gate 3 � Runtime/deployment readiness
- Static packaging: standalone `.next/static` 58/58 byte-identical, live asset
  HTTP 200; frontend served via `node .next/standalone/server.js` (the
  `next start` incompatibility is documented).
- Build identity: chunks bake `NEXT_PUBLIC_API_URL=http://localhost:8181`;
  backend health `{"status":"UP"}`; real login issues HS256 JWT; authenticated
  `/api/auth/me` and `/api/exams/my` return 200; anonymous `/api/exams/my` 401.
- Audit logging: `logback-spring.xml` routes `quizopia.security.audit` to
  `logs/security-audit.log` (daily+size rollover, 90-day/512MB cap, gzipped,
  console duplication for container collectors); context boot verified by test.
  Production collector wiring remains a deployment-environment task.
- `docs/runbooks/phase1-deploy-rollback.md` added: pre-deploy checks, backend/
  frontend deploy steps (standalone copy steps are mandatory), audit shipping,
  application-only rollback and data-restore last resort.

### Gate 4 � Integration state (pre-merge snapshot)
- Backend Task A `15171665` <- Task B `170853d` (PR #2 MERGEABLE, stacked);
  Frontend PR #1 `738a81f` MERGEABLE (Vercel PASS); System PR #3 OPEN and still
  pinning pre-P0 pointers � pointer update deferred until final approval.
- Working trees: backend/frontend clean; frontend WIP stash `pre-phase1-wip`
  intact; parent has only uncommitted submodule checkout drift + untracked
  local `issue4.json` helper file (not part of any PR).
- Merge order (when approved): Backend PR #2 (Task A <- B), then Frontend PR #1,
  then System PR #3 pointer update. No merges or pushes to main performed.

### Skipped tests (4) in the full suite
Pre-existing intentional skips with an explicit reason in the report:
`question codes are auto-generated; import code-collision detection removed`
— 1 in `QuestionImportApiIntegrationTests`, 3 in `QuestionImportServiceIntegrationTests`.
None are new to Phase 1; none mask failures.

### Gate 1 final result (2026-11-03)
CI green at final Task-B HEAD `c1b17e0`: run 41649538910 �
`Tests run: 1526, Failures: 0, Errors: 0, Skipped: 4`, zero flakes needed.
Run history: 41640341335 (mvnw exec bit, fixed + `mode 100755`),
41640499686 (stale constraint-translation test SQL pre-V16/V17, fixed),
41647599077 (CI-only STOMP ERROR-vs-CLOSE frame race � timeouts tuned to
`stomp.await-seconds` default 15s, plus transparent surefire
`rerunFailingTestsCount=2`; tracked in Quizopia_Backend issue #4 with full
evidence; reruns are reported as explicit flakes, never silent).
