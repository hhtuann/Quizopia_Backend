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
